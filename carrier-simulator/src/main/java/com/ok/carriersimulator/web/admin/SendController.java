package com.ok.carriersimulator.web.admin;

import com.ok.carriersimulator.core.CallbackRecord;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.DispatchSettings;
import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.StatsSnapshot;
import com.ok.carriersimulator.core.StatusCode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 보내기 화면. 알림을 얼마나 빨리 보낼지 정하고, 송장 번호를 지정해 보내고, 보낸 알림이 도착했는지 확인한다.
 */
@Controller
class SendController {

    private static final int CHART_BUCKETS = 30;
    private static final int SHOWN_RECORDS = 100;
    // 평균 응답 시간을 낼 때 보는 최근 기록 수
    private static final int AVERAGE_SAMPLE = 1_000;
    private static final List<Metric> CALLBACK_METRICS = List.of(Metric.CALLBACK_OK, Metric.CALLBACK_FAIL);

    private final CarrierSimulator simulator;
    private final Clock clock;

    SendController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    @GetMapping("/send")
    String send(@RequestParam(required = false) String prefix, Model model) {
        StatsSnapshot stats = simulator.stats(clock.instant());
        DispatchSettings dispatch = simulator.dispatch();
        List<StatsSnapshot.Bucket> recent = stats.buckets().subList(stats.buckets().size() - CHART_BUCKETS,
                stats.buckets().size());
        List<CallbackRecord> sample = simulator.callbackRecords(null, AVERAGE_SAMPLE);
        String prefixFilter = prefix == null ? "" : prefix.strip();

        model.addAttribute("intervalSeconds", trimmed(dispatch.interval().toMillis() / 1000.0));
        model.addAttribute("batchSize", dispatch.batchSize());
        model.addAttribute("concurrency", dispatch.concurrency());
        model.addAttribute("dispatchPaused", dispatch.paused());
        model.addAttribute("pendingCallbacks", stats.pendingCallbacks());
        model.addAttribute("sentCount", stats.total(Metric.CALLBACK_OK) + stats.total(Metric.CALLBACK_FAIL));
        model.addAttribute("okCount", stats.total(Metric.CALLBACK_OK));
        model.addAttribute("failCount", stats.total(Metric.CALLBACK_FAIL));
        model.addAttribute("averageMillis", sample.isEmpty() ? "-"
                : Math.round(sample.stream().mapToLong(CallbackRecord::elapsedMillis).average().orElse(0)) + "ms");
        model.addAttribute("chart", BarChart.stacked(recent, CALLBACK_METRICS, 600, 140));
        model.addAttribute("chartFrom", AdminSupport.time(recent.getFirst().start()));
        model.addAttribute("prefix", prefixFilter);
        model.addAttribute("records", simulator.callbackRecords(prefixFilter, SHOWN_RECORDS).stream()
                .map(SendController::row).toList());
        model.addAttribute("carriers", AdminSupport.CARRIERS);
        model.addAttribute("statuses", StatusCode.values());
        return "send";
    }

    @PostMapping("/send/settings")
    String updateSettings(@RequestParam(required = false) String intervalSeconds,
                          @RequestParam(required = false) String batchSize,
                          @RequestParam(required = false) String concurrency,
                          @RequestParam(required = false) String refresh,
                          RedirectAttributes redirect) {
        try {
            double seconds = AdminSupport.parseDouble(intervalSeconds, "보내는 간격");
            // 전송 타이머가 0.1초마다 돌아서 그보다 짧은 간격은 지킬 수 없다
            if (!(seconds >= 0.1 && seconds <= 3600)) {
                throw new IllegalArgumentException("보내는 간격은 0.1초 이상 3,600초 이하여야 합니다.");
            }
            simulator.updateDispatch(new DispatchSettings(Duration.ofMillis(Math.round(seconds * 1000)),
                    AdminSupport.parseInt(batchSize, "한 번에 보내는 개수"),
                    AdminSupport.parseInt(concurrency, "동시에 보내는 수"),
                    simulator.dispatch().paused()));
            redirect.addFlashAttribute("message", "보내는 속도를 바꿨습니다.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return AdminSupport.redirect("/send", refresh);
    }

    @PostMapping("/send/pause")
    String pause(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.updateDispatch(simulator.dispatch().withPaused(true));
        redirect.addFlashAttribute("message", "보내기를 멈췄습니다. 알림은 대기 줄에 쌓입니다.");
        return AdminSupport.redirect("/send", refresh);
    }

    @PostMapping("/send/resume")
    String resume(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.updateDispatch(simulator.dispatch().withPaused(false));
        redirect.addFlashAttribute("message", "보내기를 다시 시작했습니다.");
        return AdminSupport.redirect("/send", refresh);
    }

    @PostMapping("/send/manual")
    String sendManual(@RequestParam(required = false) String carrierId,
                      @RequestParam(required = false) String trackingNumbers,
                      @RequestParam(required = false) String status,
                      @RequestParam(required = false) String times,
                      @RequestParam(required = false) String callbackUrl,
                      @RequestParam(required = false) String refresh,
                      RedirectAttributes redirect) {
        try {
            String carrier = AdminSupport.requireText(carrierId, "택배사");
            // 줄마다 앞뒤 공백을 지우고 빈 줄과 같은 번호는 한 번만 센다
            List<ParcelKey> keys = (trackingNumbers == null ? "" : trackingNumbers).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .distinct()
                    .map(number -> new ParcelKey(carrier, number))
                    .toList();
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("송장 번호를 한 줄에 하나씩 넣어야 합니다.");
            }
            int count = AdminSupport.parseInt(times, "보낼 횟수");
            simulator.sendManual(keys, statusOf(status), count, AdminSupport.optionalHttpUrl(callbackUrl), clock.instant());
            redirect.addFlashAttribute("message",
                    "송장 " + keys.size() + "건의 알림 " + (long) keys.size() * count + "건을 대기 줄에 넣었습니다.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return AdminSupport.redirect("/send", refresh);
    }

    private static StatusCode statusOf(String status) {
        try {
            return StatusCode.valueOf(status == null ? "" : status.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("상태를 골라야 합니다.");
        }
    }

    private static RecordRow row(CallbackRecord record) {
        String response = record.responseCode() != null ? String.valueOf(record.responseCode()) : record.failure();
        return new RecordRow(AdminSupport.time(record.sentAt()), record.key().trackingNumber(), record.key().carrierId(),
                record.lastStatus() == null ? "-" : record.lastStatus().displayName(), response, record.success(),
                record.elapsedMillis() + "ms", record.attemptNo());
    }

    // 1.0은 1로, 0.5는 0.5로 보여 준다
    private static String trimmed(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    record RecordRow(String sentAt, String trackingNumber, String carrierId, String status, String response,
                     boolean success, String elapsed, int attemptNo) {
    }
}
