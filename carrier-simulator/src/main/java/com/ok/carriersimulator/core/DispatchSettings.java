package com.ok.carriersimulator.core;

import java.time.Duration;

/**
 * 알림을 얼마나 빨리 보낼지.
 *
 * @param interval    대기 줄에서 알림을 꺼내는 간격
 * @param batchSize   한 번에 꺼내는 개수
 * @param concurrency 동시에 보내는 수. 전송기가 지킨다
 * @param paused      멈춰 두면 대기 줄에 쌓이기만 한다
 */
public record DispatchSettings(Duration interval, int batchSize, int concurrency, boolean paused) {

    public DispatchSettings {
        if (interval == null || interval.isZero() || interval.isNegative() || interval.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("보내는 간격은 0보다 크고 1시간 이하여야 합니다.");
        }
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("한 번에 보내는 개수는 1 이상 10,000 이하여야 합니다.");
        }
        if (concurrency < 1 || concurrency > 200) {
            throw new IllegalArgumentException("동시에 보내는 수는 1 이상 200 이하여야 합니다.");
        }
    }

    public DispatchSettings withPaused(boolean paused) {
        return new DispatchSettings(interval, batchSize, concurrency, paused);
    }
}
