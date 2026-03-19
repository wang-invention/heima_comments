package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;


    @Override
    public Shop queryById(Long id) {
        return cacheClient.queryByIdWithLogicExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id,
                Shop.class, this::preloadShopById, 10L, TimeUnit.SECONDS);
    }


    /**
     * 使用互斥锁解决缓存击穿
     *
     * @param id
     * @return
     */
    public Shop queryByIdWithMutex(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //先尝试查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheShop)) {
            log.debug("查询缓存成功");
            return BeanUtil.toBean(cacheShop, Shop.class);
        }
        //解决缓存穿透
        if (cacheShop != null) {
            return null;
        }
        String lock = null;
        Shop shop = null;

        try {
            //实现缓存重建
            lock = RedisConstants.LOCK_SHOP_KEY + id;
            if (!tryLock(lock)) {
                //获取锁失败，则休眠并重试
                Thread.sleep(50);
                return queryByIdWithRedis(id);
            }
            //查询数据，存入缓存
            shop = getById(id);
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            log.debug("查询数据库成功");
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lock);
        }
        return shop;
    }


    /**
     * 实现缓存预热
     *
     * @param id
     * @param expire
     */
    public void preloadShopById(Long id, Long expire) {
        log.debug("开始预热");
        //查询数据，存入缓存
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            log.debug("线程被中断");
        }
        //构造RedisData
        RedisData redisData = new RedisData();
        if (shop != null) {
            redisData.setData(shop);
            redisData.setExpireTime(LocalDateTime.now().plusSeconds(expire));
        }
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 创建一个线程池
     */
    private final static Executor pool = Executors.newFixedThreadPool(10);

    /**
     * 使用逻辑过期策略防止缓存击穿
     *
     * @param id
     * @return
     */
    public Shop queryByIdWithLogicExpire(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //先尝试查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(cacheShop)) {
            log.debug("缓存为空，该数据不存在");
            return null;
        }
        RedisData redisData = JSONUtil.toBean(cacheShop, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //判断缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //没有过期直接返回
            return shop;
        }
        boolean tryLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);

        if (tryLock) {
            //获取锁成功，开启独立线程，实现缓存重建
            log.debug("获取锁成功，开始缓存重建");
            pool.execute(() -> {
                try {
                    //使用线程池实现异步更新缓存
                    preloadShopById(id, 10L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(RedisConstants.LOCK_SHOP_KEY + id);
                }

            });

        }
        return shop;
    }

    /**
     * 防止缓存穿透
     *
     * @param id
     * @return
     */
    public Shop queryByIdWithRedis(Long id) {
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //先尝试查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheShop)) {
            log.debug("查询缓存成功");
            return BeanUtil.toBean(cacheShop, Shop.class);
        }
        if (cacheShop != null) {
            return null;
        }
        //查询数据，存入缓存
        Shop shop = getById(id);
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        log.debug("查询数据库成功");
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), 30, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 尝试获取lock
     *
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS));
    }


    /**
     * 释放锁
     *
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    public List<Shop> getShopListByType(Integer typeId) {

        String key = RedisConstants.CACHE_SHOP_PAGE_TYPE_KEY + typeId;
        // 2. 先查缓存（整个店铺列表存在一个key里，不是multiGet）
        String shopListJson = stringRedisTemplate.opsForValue().get(key);

        // 3. 缓存有，直接返回
        if (StrUtil.isNotBlank(shopListJson)) {
            System.err.println("查询缓存成功" + shopListJson);
            return JSONUtil.toList(shopListJson, Shop.class);
        }
        List<Shop> shopList = query().eq("type_id", typeId).list();
        if (CollUtil.isNotEmpty(shopList)) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    JSONUtil.toJsonStr(shopList),
                    30,
                    TimeUnit.MINUTES
            );
        }
        return shopList;
    }

    @Override
    public void updateShop(Shop shop) {
        //更新数据库
        updateById(shop);
        //删除缓存
        if (shop.getId() == null) {
            throw new RuntimeException("id不能为空");
        }
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
    }

    @Override
    public Page<Shop> queryShopPageByType(Integer typeId, Integer current) {
        int size = SystemConstants.MAX_PAGE_SIZE;

        // ======================
        // 关键：缓存 key 必须带 页码 + 每页条数
        // ======================
        String key = RedisConstants.CACHE_SHOP_PAGE_TYPE_KEY
                + typeId
                + ":page:" + current
                + ":size:" + size;

        // 1. 查缓存
        String pageJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(pageJson)) {
            // 转回 Page 对象
            return JSONUtil.toBean(pageJson, Page.class);
        }

        // 2. 缓存未命中 → 真正分页查数据库
        Page<Shop> page = new Page<>(current, size);
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                .eq(Shop::getTypeId, typeId);

        // 真正分页查询
        Page<Shop> resultPage = this.page(page, wrapper);

        // 3. 缓存分页结果（5 分钟足够，不需要 30 分钟）
        if (resultPage != null) {
            stringRedisTemplate.opsForValue().set(
                    key,
                    JSONUtil.toJsonStr(resultPage),
                    5,
                    TimeUnit.MINUTES
            );
        }

        return resultPage;
    }
}
