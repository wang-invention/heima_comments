package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 创建一个线程池
     */
    private final static Executor pool = Executors.newFixedThreadPool(10);

    /**
     * 设置缓存，自动序列化，并设置过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置缓存，自动序列化，并设置过期时间
     *
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogic(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 防止缓存穿透
     *
     * @param id
     * @return
     */
    public <R, ID> R queryByIdWithRedis(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //先尝试查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(cacheShop)) {
            log.debug("查询缓存成功");
            return BeanUtil.toBean(cacheShop, type);
        }
        if (cacheShop != null) {
            return null;
        }
        //查询数据，存入缓存
        R data = dbFallback.apply(id);
        if (data == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        log.debug("查询数据库成功");
        this.set(key, data, time, unit);
        return data;
    }

    /**
     * 使用逻辑过期策略防止缓存击穿
     *
     * @param id
     * @return
     */
    public <R, T> R queryByIdWithLogicExpire(String dataKeyPrefix, String lockKeyPrefix, T id, Class<R> type
            , BiConsumer<T, Long> cacheUpdate, Long time, TimeUnit unit) {
        String dataKey = dataKeyPrefix + id;
        String lockKey = lockKeyPrefix + id;
        //先尝试查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(dataKey);
        if (StrUtil.isBlank(cacheShop)) {
            log.debug("缓存为空，该数据不存在");
            return null;
        }
        RedisData redisData = JSONUtil.toBean(cacheShop, RedisData.class);
        R data = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //判断缓存是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //没有过期直接返回
            return data;
        }
        boolean tryLock = tryLock(lockKeyPrefix + id);

        if (tryLock) {
            //获取锁成功，开启独立线程，实现缓存重建
            log.debug("获取锁成功，开始缓存重建");
            pool.execute(() -> {
                try {
                    //使用线程池实现异步更新缓存
                    cacheUpdate.accept(id, unit.toSeconds(time));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }

            });

        }
        return data;
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


}
