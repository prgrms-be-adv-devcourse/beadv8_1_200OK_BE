package com.ok.carriersimulator.web.admin;

import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.StatsSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 시간대별 누적 막대그래프의 좌표. 자바스크립트 없이 서버에서 계산해 템플릿이 SVG로 그리기만 한다.
 *
 * @param max 가장 큰 구간의 합. 세로축 눈금으로 보여 준다
 */
public record BarChart(int width, int height, List<Bar> bars, long max) {

    /**
     * 구간마다 항목을 아래부터 쌓은 막대를 만든다. 가장 큰 구간이 높이를 꽉 채운다.
     *
     * @param metrics 쌓는 순서. 앞의 것이 아래다
     */
    public static BarChart stacked(List<StatsSnapshot.Bucket> buckets, List<Metric> metrics, int width, int height) {
        long max = 0;
        for (StatsSnapshot.Bucket bucket : buckets) {
            long sum = 0;
            for (Metric metric : metrics) {
                sum += bucket.count(metric);
            }
            max = Math.max(max, sum);
        }
        List<Bar> bars = new ArrayList<>();
        if (max == 0 || buckets.isEmpty()) {
            return new BarChart(width, height, bars, max);
        }
        double slot = (double) width / buckets.size();
        int barWidth = Math.max(1, (int) Math.floor(slot * 0.8));
        for (int index = 0; index < buckets.size(); index++) {
            int x = (int) Math.round(index * slot + (slot - barWidth) / 2);
            long below = 0;
            for (Metric metric : metrics) {
                long count = bucket(buckets, index).count(metric);
                if (count == 0) {
                    continue;
                }
                // 누적 위치로 계산해, 반올림 오차가 쌓여 막대 사이가 벌어지거나 높이를 넘지 않게 한다
                int bottom = scale(below, max, height);
                int top = Math.max(bottom + 1, scale(below + count, max, height));
                bars.add(new Bar(x, height - top, barWidth, top - bottom, metric.name().toLowerCase(Locale.ROOT)));
                below += count;
            }
        }
        return new BarChart(width, height, bars, max);
    }

    private static StatsSnapshot.Bucket bucket(List<StatsSnapshot.Bucket> buckets, int index) {
        return buckets.get(index);
    }

    private static int scale(long value, long max, int height) {
        return (int) Math.round((double) value / max * height);
    }

    /**
     * @param kind 항목 이름의 소문자. 템플릿이 CSS 클래스로 써서 색을 정한다
     */
    public record Bar(int x, int y, int width, int height, String kind) {
    }
}
