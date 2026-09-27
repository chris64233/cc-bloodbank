package com.chris64233.cc.bloodbank.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用预留到期扫描。测试中可通过 bloodbank.scheduling.enabled=false 关闭。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "bloodbank.scheduling", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
