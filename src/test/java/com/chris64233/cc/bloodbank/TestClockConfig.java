package com.chris64233.cc.bloodbank;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

@TestConfiguration
public class TestClockConfig {

    /** 测试基准时间 */
    public static final Instant T0 = Instant.parse("2026-09-27T08:00:00Z");

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock(T0);
    }
}
