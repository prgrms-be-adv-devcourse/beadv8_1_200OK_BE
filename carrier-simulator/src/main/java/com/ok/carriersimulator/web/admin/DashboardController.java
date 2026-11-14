package com.ok.carriersimulator.web.admin;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.StatsSnapshot;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 현황 화면. 얼마나 조회받았고 송장이 지금 어떤 상태인지 보여 준다.
 */
@Controller
class DashboardController {

    private static final int CHART_BUCKETS = 30;
    private static final List<Metric> QUERY_METRICS = List.of(Metric.TRACK_OK, Metric.TRACK_ERROR, Metric.TRACK_NOT_FOUND);

    private final CarrierSimulator simulator;
    private final Clock clock;

    DashboardController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    @GetMapping("/")
    String dashboard(Model model) {
        StatsSnapshot stats = simulator.stats(clock.instant());
        List<StatsSnapshot.Bucket> recent = stats.buckets().subList(stats.buckets().size() - CHART_BUCKETS,
                stats.buckets().size());
        // 지금 구간은 아직 채워지는 중이라, 초당 조회 수는 바로 앞의 다 찬 구간으로 계산한다
        StatsSnapshot.Bucket lastFull = recent.get(recent.size() - 2);
        long lastFullQueries = QUERY_METRICS.stream().mapToLong(lastFull::count).sum();

        model.addAttribute("totalQueries", QUERY_METRICS.stream().mapToLong(stats::total).sum());
        model.addAttribute("queriesPerSecond", String.format(Locale.ROOT, "%.1f", lastFullQueries / 10.0));
        model.addAttribute("parcelCount", stats.parcelCount());
        // 조회 수와 그래프에는 조회 오류만 들어가고, 돌려준 오류 응답에는 등록 오류와 인증 실패까지 모두 센다
        model.addAttribute("errorResponses", stats.total(Metric.TRACK_ERROR) + stats.total(Metric.REGISTER_ERROR)
                + stats.total(Metric.UNAUTHENTICATED));
        model.addAttribute("registerCount", stats.total(Metric.REGISTER));
        model.addAttribute("pendingCallbacks", stats.pendingCallbacks());
        model.addAttribute("secondsPerDay", String.format(Locale.ROOT, "%.1f", simulator.secondsPerDay()));
        model.addAttribute("paused", simulator.paused());
        model.addAttribute("detectionP50", AdminSupport.seconds(stats.detectionP50()));
        model.addAttribute("detectionP95", AdminSupport.seconds(stats.detectionP95()));
        model.addAttribute("chart", BarChart.stacked(recent, QUERY_METRICS, 600, 140));
        model.addAttribute("chartFrom", AdminSupport.time(recent.getFirst().start()));
        model.addAttribute("statusRows", statusRows(stats.statusCounts()));
        return "dashboard";
    }

    private static List<StatusRow> statusRows(Map<String, Long> counts) {
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<StatusRow> rows = new ArrayList<>();
        counts.forEach((name, count) -> {
            // 한 건도 없는 상태는 줄이지 않고 0으로 보여 준다. 어떤 상태가 비어 있는지도 정보다
            int percent = max == 0 ? 0 : (int) Math.round(count * 100.0 / max);
            rows.add(new StatusRow(name, count, count > 0 ? Math.max(1, percent) : 0));
        });
        return rows;
    }

    record StatusRow(String name, long count, int percent) {
    }
}
