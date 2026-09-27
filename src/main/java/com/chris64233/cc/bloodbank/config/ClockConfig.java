package com.chris64233.cc.bloodbank.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 可替换时钟：生产用系统 UTC 时钟，测试可替换为固定/可变时钟。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
