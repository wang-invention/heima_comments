package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;


    private static final ExecutorService SKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();


    private IVoucherOrderService proxy;


    @PostConstruct
    private void init() {
        SKILL_ORDER_EXECUTOR.submit(new VoucherOrderTask());
    }


//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    /**
//     * 线程任务
//     */
//    private class VoucherOrderTask implements Runnable {
//
//        @Override
//        public void run() {
//            while (true) {
//                //获取队列中的订单信息
//                try {
//                    VoucherOrder take = orderTasks.take();
//                    handleVoucherOrder(take);
//                } catch (InterruptedException e) {
//                    log.info("处理订单异常", 3);
//                }
//            }
//
//        }


    private static final String stream = "streams:orders";

    /**
     * 线程任务
     */
    private class VoucherOrderTask implements Runnable {
        //消息队列处理，从stream里面获取消息，处理订单，ACK确认消息，出现异常尝试从pendingList中获取ACK确认消息
        @Override
        public void run() {
            while (true) {
                //获取消息队列中的订单信息
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(stream, ReadOffset.lastConsumed()));
                    //判断消息是否获取成功
                    if (read == null || read.isEmpty()) {
                        continue;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    //失败获取订单信息，重试
                    //成功创建订单信息
                    handleVoucherOrder(voucherOrder);
                    //ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(stream, "g1", record.getId());
                } catch (Exception e) {
                    handlePendingList();
                    log.info("处理订单异常", e);
                }
            }

        }

        private void handlePendingList() {
            while (true) {
                //获取消息队列中的订单信息
                try {
                    //获取消息队列中的订单信息
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(stream, ReadOffset.from("0")));
                    //判断消息是否获取成功
                    if (read == null || read.isEmpty()) {
                        break;
                    }
                    MapRecord<String, Object, Object> record = read.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    //成功创建订单信息
                    handleVoucherOrder(voucherOrder);
                    //ACK确认消息
                    stringRedisTemplate.opsForStream().acknowledge(stream, "g1", record.getId());
                } catch (Exception e) {
                    log.info("处理订单异常", e);
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            RLock simpleRedisLock = redissonClient.getLock("order:" + userId);
            if (!simpleRedisLock.tryLock()) {
                log.info("不允许重复下单");
                return;
            }
            try {
                proxy.createOrder(voucherOrder);
                return;
            } finally {
                simpleRedisLock.unlock();
            }
        }
    }

    private static final DefaultRedisScript<Long> SEC_KILL;

    static {
        SEC_KILL = new DefaultRedisScript<>();
        SEC_KILL.setLocation(new ClassPathResource("seckill.lua"));
        SEC_KILL.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result seckillVoucher(Long voucherId) {
        //任务将创建订单任务交给消息队列来处理
        Long userId = UserHolder.getUser().getId();
        //获取订单Id
        Long orderId = redisIdWorker.nextId("order");
        //执行lua脚本
        Long result = stringRedisTemplate.execute(SEC_KILL, Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
        //判断结果是不是为0
        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //TODO 使用stream消息队列来处理订单
        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

//    @Override
//
//    public Result seckillVoucher(Long voucherId) {
//        //任务将创建订单任务交给消息队列来处理
//        Long userId = UserHolder.getUser().getId();
//        //获取订单Id
//        Long orderId = redisIdWorker.nextId("order");
//        //执行lua脚本
//        Long result = stringRedisTemplate.execute(SEC_KILL, Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
//        //判断结果是不是为0
//        int r = result.intValue();
//        if (r != 0) {
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //TODO 使用stream消息队列来处理订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//        voucherOrder.setVoucherId(voucherId);
//        this.proxy = (IVoucherOrderService) AopContext.currentProxy();
//        orderTasks.add(voucherOrder);
//        return Result.ok(orderId);
//    }


//    public Result seckillVoucher(Long voucherId) {
//        //查询优惠券
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //检查活动是否开始
//        if (seckillVoucher == null) {
//            return Result.fail("活动不存在");
//        }
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        if (beginTime.isAfter(LocalDateTime.now())) {
//            return Result.fail("活动尚未开始");
//        }
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        if (endTime.isBefore(LocalDateTime.now())) {
//            return Result.fail("活动已结束");
//        }
//        //检查并更新库存
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId);
//        RLock simpleRedisLock = redissonClient. getLock("order:" + userId);
//        if (!simpleRedisLock.tryLock()) {
//            return Result.fail("请勿重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createOrder(voucherId);
//        } finally {
//            simpleRedisLock.unlock();
//        }
//
//    }

    @Transactional
    public void createOrder(VoucherOrder voucherOrder) {
        //一人一单判断
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return;
        }
        boolean result = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0).
                update();
        if (!result) {
            return;
        }
        save(voucherOrder);

    }
}
