package com.ok.carriersimulator.web.admin;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 실험 설정 화면. 재생 속도와 일부러 넣는 장애를 실행 중에 바꾸고, 쌓인 통계와 송장을 지운다.
 */
@Controller
class SettingsController {

    private final CarrierSimulator simulator;
    private final Clock clock;

    SettingsController(CarrierSimulator simulator, Clock clock) {
        this.simulator = simulator;
        this.clock = clock;
    }

    @GetMapping("/settings")
    String settings(Model model) {
        FaultSettings faults = simulator.faults();
        model.addAttribute("secondsPerDay", String.format(Locale.ROOT, "%.1f", simulator.secondsPerDay()));
        model.addAttribute("paused", simulator.paused());
        model.addAttribute("responseDelayMillis", faults.responseDelayMillis());
        model.addAttribute("errorPercent", faults.errorPercent());
        model.addAttribute("callbackCopies", faults.callbackCopies());
        model.addAttribute("shuffleCallbacks", faults.shuffleCallbacks());
        return "settings";
    }

    @PostMapping("/settings/speed")
    String changeSpeed(@RequestParam(required = false) String secondsPerDay,
                       @RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        try {
            double seconds = AdminSupport.parseDouble(secondsPerDay, "재생 속도");
            if (!(seconds >= 0.1 && seconds <= 86_400)) {
                throw new IllegalArgumentException("재생 속도는 하루를 0.1초 이상 86,400초 이하로 재생해야 합니다.");
            }
            simulator.changeSpeed(seconds, clock.instant());
            redirect.addFlashAttribute("message", "재생 속도를 바꿨습니다. 이미 일어난 사건의 시각은 그대로입니다.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/pause")
    String pause(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.pause(clock.instant());
        redirect.addFlashAttribute("message", "재생을 멈췄습니다. 송장 상태가 더 진행하지 않습니다.");
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/resume")
    String resume(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.resume(clock.instant());
        redirect.addFlashAttribute("message", "재생을 다시 시작했습니다.");
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/skip-day")
    String skipDay(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.skipDay(clock.instant());
        redirect.addFlashAttribute("message", "가상 시간으로 하루를 건너뛰었습니다.");
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/faults")
    String updateFaults(@RequestParam(required = false) String responseDelayMillis,
                        @RequestParam(required = false) String errorPercent,
                        @RequestParam(required = false) String callbackCopies,
                        @RequestParam(required = false) String shuffleCallbacks,
                        @RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        try {
            // 체크박스는 켰을 때만 값이 온다
            simulator.updateFaults(new FaultSettings(
                    AdminSupport.parseInt(responseDelayMillis, "응답 지연"),
                    AdminSupport.parseInt(errorPercent, "오류 비율"),
                    AdminSupport.parseInt(callbackCopies, "알림 중복 횟수"),
                    shuffleCallbacks != null));
            redirect.addFlashAttribute("message", "장애 설정을 바꿨습니다.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/expire-tokens")
    String expireTokens(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.expireTokens();
        redirect.addFlashAttribute("message", "발급한 토큰을 모두 만료시켰습니다. 다음 요청은 UNAUTHENTICATED를 받습니다.");
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/clear-stats")
    String clearStats(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.clearStats();
        redirect.addFlashAttribute("message", "통계와 보낸 기록을 지웠습니다. 송장은 그대로입니다.");
        return AdminSupport.redirect("/settings", refresh);
    }

    @PostMapping("/settings/clear-all")
    String clearAll(@RequestParam(required = false) String refresh, RedirectAttributes redirect) {
        simulator.clearAll();
        redirect.addFlashAttribute("message", "송장, 대기 중인 알림, 통계를 모두 지웠습니다.");
        return AdminSupport.redirect("/settings", refresh);
    }
}
