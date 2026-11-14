package com.ok.carriersimulator.web.admin;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.ParcelDetail;
import com.ok.carriersimulator.core.ParcelFilter;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.ParcelPage;
import com.ok.carriersimulator.core.ParcelView;
import com.ok.carriersimulator.core.Scenario;
import com.ok.carriersimulator.core.StatusCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 송장 화면. 시험용 더미 송장을 만들고, 송장 목록과 한 건의 사건 이력을 보여 준다.
 */
@Controller
class ParcelController {

    private static final int PAGE_SIZE = 50;
    // 만든 번호를 복사용으로 보여 주는 최대 개수. 10만 건을 만들어도 화면이 무거워지지 않게 한다
    private static final int MAX_SHOWN_NUMBERS = 1_000;

    private final CarrierSimulator simulator;
    private final Clock clock;

    ParcelController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    // 조회 조건은 틀린 값이 와도 오류를 내지 않고 조건이 없는 것으로 본다. 주소창을 고쳐 들어와도 화면이 열리게 한다
    @GetMapping("/parcels")
    String list(@RequestParam(required = false) String prefix,
                @RequestParam(required = false) String scenario,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String page,
                @RequestParam(required = false) String refresh,
                Model model) {
        Scenario scenarioFilter = enumOrNull(Scenario.class, scenario);
        StatusCode statusFilter = enumOrNull(StatusCode.class, status);
        String prefixFilter = prefix == null ? "" : prefix.strip();
        int pageNumber = pageNumber(page);
        Instant now = clock.instant();
        ParcelPage result = simulator.parcels(new ParcelFilter(prefixFilter, scenarioFilter, statusFilter), pageNumber,
                PAGE_SIZE, now);
        long totalPages = Math.max(1, (result.total() + PAGE_SIZE - 1) / PAGE_SIZE);

        String query = "?prefix=" + URLEncoder.encode(prefixFilter, StandardCharsets.UTF_8)
                + "&scenario=" + (scenarioFilter == null ? "" : scenarioFilter.name())
                + "&status=" + (statusFilter == null ? "" : statusFilter.name())
                + (AdminSupport.refreshSeconds(refresh) > 0 ? "&refresh=" + AdminSupport.refreshSeconds(refresh) : "");
        model.addAttribute("rows", result.items().stream().map(ParcelController::row).toList());
        model.addAttribute("total", result.total());
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("previousLink", pageNumber > 0 ? "/parcels" + query + "&page=" + (pageNumber - 1) : null);
        model.addAttribute("nextLink", pageNumber + 1 < totalPages ? "/parcels" + query + "&page=" + (pageNumber + 1) : null);
        model.addAttribute("prefix", prefixFilter);
        model.addAttribute("scenarioFilter", scenarioFilter);
        model.addAttribute("statusFilter", statusFilter);
        model.addAttribute("scenarios", Scenario.values());
        model.addAttribute("statuses", StatusCode.values());
        model.addAttribute("carriers", AdminSupport.CARRIERS);
        return "parcels";
    }

    @PostMapping("/parcels/dummies")
    String createDummies(@RequestParam(required = false) String scenario,
                         @RequestParam(required = false) String carrierId,
                         @RequestParam(required = false) String count,
                         @RequestParam(required = false) String callbackUrl,
                         @RequestParam(required = false) String refresh,
                         RedirectAttributes redirect) {
        try {
            Scenario chosen = enumOrNull(Scenario.class, scenario);
            if (chosen == null) {
                throw new IllegalArgumentException("시나리오를 골라야 합니다.");
            }
            List<ParcelKey> created = simulator.createDummies(chosen, AdminSupport.requireText(carrierId, "택배사"),
                    AdminSupport.parseInt(count, "개수"), AdminSupport.optionalHttpUrl(callbackUrl), clock.instant());
            redirect.addFlashAttribute("message", "더미 송장 " + created.size() + "건을 만들었습니다. (" + chosen.displayName() + ")");
            redirect.addFlashAttribute("createdNumbers", created.stream()
                    .limit(MAX_SHOWN_NUMBERS)
                    .map(ParcelKey::trackingNumber)
                    .collect(Collectors.joining("\n")));
            redirect.addFlashAttribute("createdHidden", Math.max(0, created.size() - MAX_SHOWN_NUMBERS));
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return AdminSupport.redirect("/parcels", refresh);
    }

    @GetMapping("/parcels/{carrierId}/{trackingNumber}")
    String detail(@PathVariable String carrierId, @PathVariable String trackingNumber, Model model) {
        ParcelDetail detail = simulator.parcel(new ParcelKey(carrierId, trackingNumber), clock.instant())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "없는 송장입니다."));
        model.addAttribute("parcel", row(detail.summary()));
        model.addAttribute("registrationUrl", detail.registration() == null ? null : detail.registration().callbackUrl());
        model.addAttribute("registrationExpires", detail.registration() == null ? null
                : AdminSupport.time(detail.registration().expirationTime()));
        model.addAttribute("events", detail.events().stream().map(ParcelController::eventRow).toList());
        return "parcel-detail";
    }

    private static ParcelRow row(ParcelView view) {
        String status = view.lastStatus() == null ? "없는 송장" : view.lastStatus().displayName();
        return new ParcelRow(view.key().carrierId(), view.key().trackingNumber(),
                "/parcels/" + encodePath(view.key().carrierId()) + "/" + encodePath(view.key().trackingNumber()),
                view.scenario().displayName() + (view.manual() ? " (수동)" : ""), status,
                AdminSupport.time(view.firstQueriedAt()), view.queryCount(), AdminSupport.time(view.lastQueriedAt()));
    }

    private static EventRow eventRow(ParcelDetail.EventTrace trace) {
        Duration latency = trace.firstFetchedAt() == null ? null
                : Duration.between(trace.event().time(), trace.firstFetchedAt());
        return new EventRow(trace.event().status().displayName(), trace.event().location(),
                trace.event().description(), AdminSupport.time(trace.event().time()),
                AdminSupport.time(trace.firstFetchedAt()), AdminSupport.seconds(latency));
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int pageNumber(String page) {
        try {
            return Math.max(0, Integer.parseInt(page == null ? "0" : page.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record ParcelRow(String carrierId, String trackingNumber, String link, String scenario, String status,
                     String firstQueriedAt, long queryCount, String lastQueriedAt) {
    }

    record EventRow(String status, String location, String description, String occurredAt, String firstFetchedAt,
                    String latency) {
    }
}
