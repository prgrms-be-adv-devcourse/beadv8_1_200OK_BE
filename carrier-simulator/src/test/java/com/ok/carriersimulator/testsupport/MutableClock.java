package com.ok.carriersimulator.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트가 직접 움직이는 시계. 기다리지 않고 시간을 흘려 시나리오 진행을 확인한다.
 */
public class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    private volatile Instant now = START;

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
