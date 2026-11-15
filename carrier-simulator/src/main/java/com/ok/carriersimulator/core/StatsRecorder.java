package com.ok.carriersimulator.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 항목별 누적 수와 10초 단위 구간 수를 센다. 구간은 최근 1시간만 둔다.
 */
final class StatsRecorder {

    static final int BUCKET_SECONDS = 10;
    static final int BUCKET_COUNT = 360;

    private final Map<Metric, Long> totals = new EnumMap<>(Metric.class);
    private final TreeMap<Long, Map<Metric, Long>> buckets = new TreeMap<>();

    void record(Metric metric, Instant now) {
        totals.merge(metric, 1L, Long::sum);
        long index = indexOf(now);
        buckets.computeIfAbsent(index, key -> new EnumMap<>(Metric.class)).merge(metric, 1L, Long::sum);
        buckets.headMap(index - BUCKET_COUNT + 1).clear();
    }

    Map<Metric, Long> totals() {
        return new EnumMap<>(totals);
    }

    /**
     * 지금 구간까지 최근 1시간을 시간 순으로 돌려준다. 기록이 없는 구간은 비어 있는 채로 들어 있다.
     */
    List<StatsSnapshot.Bucket> buckets(Instant now) {
        long last = indexOf(now);
        List<StatsSnapshot.Bucket> result = new ArrayList<>(BUCKET_COUNT);
        for (long index = last - BUCKET_COUNT + 1; index <= last; index++) {
            Map<Metric, Long> counts = buckets.get(index);
            result.add(new StatsSnapshot.Bucket(Instant.ofEpochSecond(index * BUCKET_SECONDS),
                    counts == null ? Map.of() : new EnumMap<>(counts)));
        }
        return result;
    }

    void clear() {
        totals.clear();
        buckets.clear();
    }

    private static long indexOf(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), BUCKET_SECONDS);
    }
}
