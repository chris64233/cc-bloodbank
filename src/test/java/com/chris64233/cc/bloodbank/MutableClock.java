package com.chris64233.cc.bloodbank;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可变时钟：可设置绝对时间或向前推进。
 */
public class MutableClock extends Clock {

    private Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant) {
        this.instant = instant;
        this.zone = ZoneOffset.UTC;
    }

    public synchronized void setInstant(Instant instant) {
        this.instant = instant;
    }

    public synchronized void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public synchronized Instant instant() {
        return instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
