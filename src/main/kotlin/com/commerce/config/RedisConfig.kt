package com.commerce.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedisConfig(
    private val redisProperties: RedisProperties
) {

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        // 명시적 타임아웃/재시도 — Redis 장애 시 예측 가능한 시간 내에 실패하도록(무한 대기 방지).
        config.useSingleServer()
            .setAddress("redis://${redisProperties.host}:${redisProperties.port}")
            .setConnectTimeout(3000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(500)
        return Redisson.create(config)
    }
}
