package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.sync.RedisGeoCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collector;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);


    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 1000; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("耗时：" + (end - begin));

    }


    @Test
    void testSaveShop() {
        shopService.preloadShopById(9L, 10L);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    void testSaveShop2() {
        //1.查询店铺信息
        List<Shop> list = shopService.list();
        //按照typeId进行分组
        Map<Long, List<Shop>> listMap = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> longListEntry : listMap.entrySet()) {
            Long typeId = longListEntry.getKey();
            List<Shop> shopList = longListEntry.getValue();

            // 拼接Geo的key
            String key = "shop:geo:" + typeId;

            Map<String, Point> locationMap = new HashMap<>();
            for (Shop shop : shopList) {

                locationMap.put(shop.getId().toString(), new Point(shop.getX(), shop.getY()));
            }
            stringRedisTemplate.opsForGeo().add(key, locationMap);
        }
    }


}
