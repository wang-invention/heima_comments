package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 时间戳起始点
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    private static final long COUNT_BITS = 32;

    public long nextId(String prefix) {
        //1.生成时间戳
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + format);
        return timestamp << COUNT_BITS | count;
    }

}
