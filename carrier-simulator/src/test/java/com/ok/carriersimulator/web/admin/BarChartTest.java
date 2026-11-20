package com.ok.carriersimulator.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.StatsSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BarChartTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");
    private static final List<Metric> METRICS = List.of(Metric.TRACK_OK, Metric.TRACK_ERROR);

    private static StatsSnapshot.Bucket bucket(long ok, long error) {
        return new StatsSnapshot.Bucket(START, Map.of(Metric.TRACK_OK, ok, Metric.TRACK_ERROR, error));
    }

    @Test
    void 가장_큰_구간이_높이를_꽉_채운다() {
        // when
        BarChart sut = BarChart.stacked(List.of(bucket(50, 0), bucket(100, 0)), METRICS, 200, 100);

        // then
        assertThat(sut.bars()).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(sut.max()).isEqualTo(100);
            softly.assertThat(sut.bars().get(0).height()).isEqualTo(50);
            softly.assertThat(sut.bars().get(1).height()).isEqualTo(100);
            softly.assertThat(sut.bars().get(1).y()).isZero();
            softly.assertThat(sut.bars().get(0).x()).isLessThan(sut.bars().get(1).x());
            softly.assertThat(sut.bars().get(1).x() + sut.bars().get(1).width()).isLessThanOrEqualTo(200);
        });
    }

    @Test
    void 값이_0인_구간과_항목은_막대가_없다() {
        // when
        BarChart sut = BarChart.stacked(List.of(bucket(0, 0), bucket(10, 0)), METRICS, 200, 100);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.bars()).hasSize(1);
            softly.assertThat(sut.bars().getFirst().kind()).isEqualTo("track_ok");
        });
    }

    @Test
    void 누적_막대는_아래부터_빈틈없이_쌓인다() {
        // when
        BarChart sut = BarChart.stacked(List.of(bucket(30, 10)), METRICS, 200, 100);

        // then
        BarChart.Bar ok = sut.bars().get(0);
        BarChart.Bar error = sut.bars().get(1);
        assertSoftly(softly -> {
            softly.assertThat(ok.kind()).isEqualTo("track_ok");
            softly.assertThat(error.kind()).isEqualTo("track_error");
            softly.assertThat(ok.y() + ok.height()).isEqualTo(100);
            softly.assertThat(error.y() + error.height()).isEqualTo(ok.y());
            softly.assertThat(error.y()).isZero();
            softly.assertThat(ok.height()).isEqualTo(75);
        });
    }

    @Test
    void 아주_작은_값도_한_칸은_보인다() {
        // when
        BarChart sut = BarChart.stacked(List.of(bucket(1, 0), bucket(10_000, 0)), METRICS, 200, 100);

        // then
        assertThat(sut.bars().getFirst().height()).isEqualTo(1);
    }

    @Test
    void 구간이_비어_있거나_모두_0이어도_그려진다() {
        // when
        BarChart empty = BarChart.stacked(List.of(), METRICS, 200, 100);
        BarChart zero = BarChart.stacked(List.of(bucket(0, 0)), METRICS, 200, 100);

        // then
        assertSoftly(softly -> {
            softly.assertThat(empty.bars()).isEmpty();
            softly.assertThat(empty.max()).isZero();
            softly.assertThat(zero.bars()).isEmpty();
        });
    }
}
