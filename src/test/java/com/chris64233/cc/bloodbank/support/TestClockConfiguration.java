package com.chris64233.cc.bloodbank.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/**
 * 测试时钟配置：以 {@link MutableClock} 替换生产系统时钟。
 */
@Configuration
public class TestClockConfiguration {

    public static final Instant INITIAL_TIME = Instant.parse("2026-09-27T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(INITIAL_TIME);
    }
}
