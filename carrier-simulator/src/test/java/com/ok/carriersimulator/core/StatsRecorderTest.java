package com.ok.carriersimulator.core;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsRecorderTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void 십_초_단위로_묶는다() {
        // given
        StatsRecorder sut = new StatsRecorder();

        // when
        sut.record(Metric.TRACK_OK, START);
        sut.record(Metric.TRACK_OK, START.plusSeconds(9));
        sut.record(Metric.TRACK_ERROR, START.plusSeconds(9));
        sut.record(Metric.TRACK_OK, START.plusSeconds(10));
        List<StatsSnapshot.Bucket> buckets = sut.buckets(START.plusSeconds(15));

        // then
        StatsSnapshot.Bucket previous = buckets.get(buckets.size() - 2);
        StatsSnapshot.Bucket last = buckets.getLast();
        assertSoftly(softly -> {
            softly.assertThat(buckets).hasSize(360);
            softly.assertThat(previous.start()).isEqualTo(START);
            softly.assertThat(previous.count(Metric.TRACK_OK)).isEqualTo(2);
            softly.assertThat(previous.count(Metric.TRACK_ERROR)).isEqualTo(1);
            softly.assertThat(last.start()).isEqualTo(START.plusSeconds(10));
            softly.assertThat(last.count(Metric.TRACK_OK)).isEqualTo(1);
            softly.assertThat(sut.totals()).containsEntry(Metric.TRACK_OK, 3L).containsEntry(Metric.TRACK_ERROR, 1L);
        });
    }

    @Test
    void 한_시간이_지난_구간은_버리고_누적은_남긴다() {
        // given
        StatsRecorder sut = new StatsRecorder();
        sut.record(Metric.CALLBACK_OK, START);

        // when
        sut.record(Metric.CALLBACK_OK, START.plusSeconds(3600));
        List<StatsSnapshot.Bucket> buckets = sut.buckets(START.plusSeconds(3600));

        // then
        assertSoftly(softly -> {
            softly.assertThat(buckets.getFirst().start()).isEqualTo(START.plusSeconds(10));
            softly.assertThat(buckets.stream().mapToLong(bucket -> bucket.count(Metric.CALLBACK_OK)).sum()).isEqualTo(1);
            softly.assertThat(sut.totals()).containsEntry(Metric.CALLBACK_OK, 2L);
        });
    }

    @Test
    void 빈_구간은_0으로_채운다() {
        // given
        StatsRecorder sut = new StatsRecorder();

        // when
        List<StatsSnapshot.Bucket> buckets = sut.buckets(START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(buckets).hasSize(360);
            softly.assertThat(buckets).allSatisfy(bucket -> softly.assertThat(bucket.count(Metric.TRACK_OK)).isZero());
            softly.assertThat(buckets.getLast().start()).isEqualTo(START);
        });
    }

    @Test
    void 지우면_누적과_구간이_모두_비워진다() {
        // given
        StatsRecorder sut = new StatsRecorder();
        sut.record(Metric.REGISTER, START);

        // when
        sut.clear();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.totals()).isEmpty();
            softly.assertThat(sut.buckets(START).getLast().count(Metric.REGISTER)).isZero();
        });
    }
}
