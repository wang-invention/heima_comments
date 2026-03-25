package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 单机Redis
        config.useSingleServer()
                .setAddress("redis://101.132.17.101:6379")
                .setPassword("redis_jhjh5p")
                .setDatabase(0);

        return Redisson.create(config);
    }
}