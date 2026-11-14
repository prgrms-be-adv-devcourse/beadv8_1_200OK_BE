package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리 화면에 보여 줄 통계.
 *
 * @param buckets      10초 단위로 묶은 최근 1시간. 시간 순이고 빈 구간도 0으로 들어 있다
 * @param statusCounts 송장의 현재 상태별 개수. 키는 상태의 한글 이름이고 조회되지 않는 송장은 "없는 송장"이다
 * @param detectionP50 사건이 일어난 뒤 처음 조회해 가기까지 걸린 시간의 중앙값. 표본이 없으면 null
 * @param detectionP95 같은 값의 95번째 백분위수. 표본이 없으면 null
 */
public record StatsSnapshot(Map<Metric, Long> totals, List<Bucket> buckets, Map<String, Long> statusCounts,
                            long parcelCount, long pendingCallbacks, Duration detectionP50, Duration detectionP95) {

    public long total(Metric metric) {
        return totals.getOrDefault(metric, 0L);
    }

    public record Bucket(Instant start, Map<Metric, Long> counts) {

        public long count(Metric metric) {
            return counts.getOrDefault(metric, 0L);
        }
    }
}
