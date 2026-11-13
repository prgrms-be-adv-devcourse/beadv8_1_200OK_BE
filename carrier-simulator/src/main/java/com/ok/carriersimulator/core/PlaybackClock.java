package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 실제 시간과 가상 시간의 대응을 기록한다. 가상 시간은 재생 속도만큼 빨리 흐르고, 멈추거나 건너뛸 수 있다.
 * 속도를 바꾸고 멈춘 이력을 구간으로 남겨 두어, 이미 지난 가상 시각이 실제로 언제였는지를 나중에도 똑같이 답한다.
 */
public final class PlaybackClock {

    private static final double SECONDS_PER_DAY = 86_400;

    private final List<Segment> segments = new ArrayList<>();
    private double secondsPerDay;
    private boolean paused;

    /**
     * @param realStart     시작 시각. 가상 시각도 이 시각에서 시작한다
     * @param secondsPerDay 가상 하루를 실제 몇 초로 재생할지
     */
    public PlaybackClock(Instant realStart, double secondsPerDay) {
        requirePositive(secondsPerDay);
        this.secondsPerDay = secondsPerDay;
        segments.add(new Segment(realStart, realStart, rate()));
    }

    public synchronized Instant virtualNow(Instant realNow) {
        Segment last = segments.getLast();
        return last.virtualAt(realNow);
    }

    /**
     * 가상 시각에 도달한 실제 시각을 돌려준다. 건너뛴 구간 안의 가상 시각은 건너뛴 순간이다.
     *
     * @throws IllegalArgumentException 아직 도달하지 않은 가상 시각이면
     */
    public synchronized Instant realTimeOf(Instant virtual, Instant realNow) {
        if (virtual.isAfter(virtualNow(realNow))) {
            throw new IllegalArgumentException("아직 도달하지 않은 가상 시각입니다: " + virtual);
        }
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (!virtual.isAfter(segment.virtualStart)) {
                return segment.realStart;
            }
            Instant realEnd = i + 1 < segments.size() ? segments.get(i + 1).realStart : realNow;
            if (!virtual.isAfter(segment.virtualAt(realEnd))) {
                return segment.realAt(virtual);
            }
        }
        return realNow;
    }

    public synchronized void changeSpeed(double secondsPerDay, Instant realNow) {
        requirePositive(secondsPerDay);
        this.secondsPerDay = secondsPerDay;
        startSegment(realNow, virtualNow(realNow));
    }

    public synchronized void pause(Instant realNow) {
        Instant virtual = virtualNow(realNow);
        paused = true;
        startSegment(realNow, virtual);
    }

    public synchronized void resume(Instant realNow) {
        Instant virtual = virtualNow(realNow);
        paused = false;
        startSegment(realNow, virtual);
    }

    /**
     * 가상 시간을 그만큼 앞으로 건너뛴다.
     */
    public synchronized void skip(Duration virtual, Instant realNow) {
        if (virtual.isNegative()) {
            throw new IllegalArgumentException("건너뛸 시간은 0 이상이어야 합니다.");
        }
        startSegment(realNow, virtualNow(realNow).plus(virtual));
    }

    public synchronized boolean paused() {
        return paused;
    }

    public synchronized double secondsPerDay() {
        return secondsPerDay;
    }

    private void startSegment(Instant realNow, Instant virtualNow) {
        segments.add(new Segment(realNow, virtualNow, rate()));
    }

    private double rate() {
        return paused ? 0 : SECONDS_PER_DAY / secondsPerDay;
    }

    private static void requirePositive(double secondsPerDay) {
        if (!(secondsPerDay > 0) || Double.isInfinite(secondsPerDay)) {
            throw new IllegalArgumentException("재생 속도는 0보다 커야 합니다.");
        }
    }

    /**
     * 속도가 일정한 한 구간.
     *
     * @param rate 실제 1초에 흐르는 가상 초. 멈춘 구간은 0이다
     */
    private record Segment(Instant realStart, Instant virtualStart, double rate) {

        // 가상 시간은 실제보다 수십만 배 빨리 흘러, 나노초를 long으로 곱하면 몇 시간 만에 넘쳐 시간이 멈춘다.
        // 그래서 초 단위 실수로 계산하고 초와 나노초로 나눠 더한다
        Instant virtualAt(Instant real) {
            return plusSeconds(virtualStart, Math.max(0, seconds(Duration.between(realStart, real))) * rate);
        }

        Instant realAt(Instant virtual) {
            if (rate == 0) {
                return realStart;
            }
            return plusSeconds(realStart, seconds(Duration.between(virtualStart, virtual)) / rate);
        }

        private static double seconds(Duration duration) {
            return duration.getSeconds() + duration.getNano() / 1e9;
        }

        private static Instant plusSeconds(Instant base, double seconds) {
            long whole = (long) Math.floor(seconds);
            return base.plusSeconds(whole).plusNanos(Math.round((seconds - whole) * 1e9));
        }
    }
}
