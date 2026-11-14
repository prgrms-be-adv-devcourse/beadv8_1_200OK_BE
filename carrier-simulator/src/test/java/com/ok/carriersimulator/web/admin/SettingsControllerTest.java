package com.ok.carriersimulator.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ok.carriersimulator.core.Admission;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.Metric;
import com.ok.carriersimulator.core.Operation;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.Scenario;
import com.ok.carriersimulator.core.StatusCode;
import com.ok.carriersimulator.core.TrackResult;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SimulatorWebTest
class SettingsControllerTest {

    private static final ParcelKey NORMAL = new ParcelKey("kr.cjlogistics", "901000000001");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private CarrierSimulator simulator;
    @Autowired
    private MutableClock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private StatusCode lastStatus() {
        TrackResult result = simulator.track(NORMAL, clock.instant());
        return ((TrackResult.Found) result).lastEvent().status();
    }

    @Test
    void 실험_설정_화면이_열린다() throws Exception {
        // when & then
        mockMvc.perform(get("/settings"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("secondsPerDay", "24.0"))
                .andExpect(model().attribute("errorPercent", 0))
                .andExpect(content().string(containsString("일부러 넣는 장애")))
                .andExpect(content().string(containsString("되돌릴 수 없습니다")));
    }

    @Test
    void 재생_속도를_바꾼다() throws Exception {
        // given: 가상 1시간 = 1초에서 시작한 정상 송장
        simulator.track(NORMAL, clock.instant());

        // when: 하루를 12초로(두 배 빠르게) 바꾸면 2초 뒤 집화(가상 4시간)가 된다
        mockMvc.perform(post("/settings/speed").param("secondsPerDay", "12").param("refresh", "10"))
                .andExpect(redirectedUrl("/settings?refresh=10"))
                .andExpect(flash().attributeExists("message"));
        clock.advance(Duration.ofSeconds(2));

        // then
        assertSoftly(softly -> {
            softly.assertThat(simulator.secondsPerDay()).isEqualTo(12);
            softly.assertThat(lastStatus()).isEqualTo(StatusCode.AT_PICKUP);
        });
    }

    @Test
    void 일시정지하면_진행이_멈추고_다시_재생하면_이어진다() throws Exception {
        // given
        simulator.track(NORMAL, clock.instant());

        // when
        mockMvc.perform(post("/settings/pause")).andExpect(redirectedUrl("/settings"));
        clock.advance(Duration.ofSeconds(100));
        StatusCode whilePaused = lastStatus();
        mockMvc.perform(get("/settings")).andExpect(content().string(containsString("일시정지 상태입니다.")));
        mockMvc.perform(post("/settings/resume"));
        clock.advance(Duration.ofSeconds(4));

        // then
        assertSoftly(softly -> {
            softly.assertThat(whilePaused).isEqualTo(StatusCode.INFORMATION_RECEIVED);
            softly.assertThat(simulator.paused()).isFalse();
            softly.assertThat(lastStatus()).isEqualTo(StatusCode.AT_PICKUP);
        });
    }

    @Test
    void 하루를_건너뛰면_정상_송장이_하루만큼_진행한다() throws Exception {
        // given
        simulator.track(NORMAL, clock.instant());

        // when: 가상 24시간 뒤에는 간선 하차(20시간)까지 진행해 있다
        mockMvc.perform(post("/settings/skip-day")).andExpect(flash().attributeExists("message"));

        // then
        TrackResult.Found result = (TrackResult.Found) simulator.track(NORMAL, clock.instant());
        assertSoftly(softly -> {
            softly.assertThat(result.events()).hasSize(4);
            softly.assertThat(result.lastEvent().status()).isEqualTo(StatusCode.IN_TRANSIT);
        });
    }

    @Test
    void 장애_설정을_바꾼다() throws Exception {
        // when
        mockMvc.perform(post("/settings/faults")
                        .param("responseDelayMillis", "250").param("errorPercent", "30").param("callbackCopies", "3")
                        .param("shuffleCallbacks", "on"))
                .andExpect(flash().attribute("message", "장애 설정을 바꿨습니다."));

        // then
        assertThat(simulator.faults()).isEqualTo(new FaultSettings(250, 30, 3, true));
        mockMvc.perform(get("/settings"))
                .andExpect(model().attribute("callbackCopies", 3))
                .andExpect(model().attribute("shuffleCallbacks", true));
    }

    @Test
    void 섞기_체크를_빼고_보내면_꺼진다() throws Exception {
        // given
        simulator.updateFaults(new FaultSettings(0, 0, 1, true));

        // when
        mockMvc.perform(post("/settings/faults")
                .param("responseDelayMillis", "0").param("errorPercent", "0").param("callbackCopies", "1"));

        // then
        assertThat(simulator.faults().shuffleCallbacks()).isFalse();
    }

    @Test
    void 토큰을_만료시키면_발급한_토큰이_거절된다() throws Exception {
        // given
        String token = simulator.issueToken(clock.instant());

        // when
        mockMvc.perform(post("/settings/expire-tokens")).andExpect(flash().attributeExists("message"));

        // then
        assertThat(simulator.admit(token, Operation.TRACK, clock.instant()).rejection()).isEqualTo(Admission.Rejection.UNAUTHENTICATED);
    }

    @Test
    void 통계만_지우면_송장은_남고_모두_지우면_송장도_사라진다() throws Exception {
        // given
        simulator.createDummies(Scenario.NORMAL, "kr.cjlogistics", 3, "http://localhost:8080/callback", clock.instant());
        simulator.track(NORMAL, clock.instant());

        // when
        mockMvc.perform(post("/settings/clear-stats"));
        long queriesAfterClearStats = simulator.stats(clock.instant()).total(Metric.TRACK_OK);
        long parcelsAfterClearStats = simulator.stats(clock.instant()).parcelCount();
        mockMvc.perform(post("/settings/clear-all")).andExpect(redirectedUrl("/settings"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(queriesAfterClearStats).isZero();
            softly.assertThat(parcelsAfterClearStats).isEqualTo(3);
            softly.assertThat(simulator.stats(clock.instant()).parcelCount()).isZero();
            softly.assertThat(simulator.stats(clock.instant()).pendingCallbacks()).isZero();
        });
    }

    @ParameterizedTest
    @MethodSource("잘못된_속도")
    void 재생_속도가_잘못되면_안내_문구가_보이고_속도는_그대로다(String secondsPerDay, String message) throws Exception {
        // when & then
        mockMvc.perform(post("/settings/speed").param("secondsPerDay", secondsPerDay))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", message));
        assertThat(simulator.secondsPerDay()).isEqualTo(24);
    }

    static Stream<Arguments> 잘못된_속도() {
        String range = "재생 속도는 하루를 0.1초 이상 86,400초 이하로 재생해야 합니다.";
        return Stream.of(
                Arguments.of("0", range),
                Arguments.of("-10", range),
                Arguments.of("0.01", range),
                Arguments.of("86401", range),
                Arguments.of("Infinity", range),
                Arguments.of("NaN", range),
                Arguments.of("빠르게", "재생 속도에는 숫자를 넣어야 합니다."),
                Arguments.of("", "재생 속도에는 숫자를 넣어야 합니다."));
    }

    @ParameterizedTest
    @MethodSource("잘못된_장애_설정")
    void 장애_설정이_잘못되면_안내_문구가_보이고_설정은_그대로다(String delay, String errorPercent, String copies,
                                             String message) throws Exception {
        // when & then
        mockMvc.perform(post("/settings/faults")
                        .param("responseDelayMillis", delay).param("errorPercent", errorPercent)
                        .param("callbackCopies", copies))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", message));
        assertThat(simulator.faults()).isEqualTo(FaultSettings.none());
    }

    static Stream<Arguments> 잘못된_장애_설정() {
        return Stream.of(
                Arguments.of("abc", "0", "1", "응답 지연에는 정수를 넣어야 합니다."),
                Arguments.of("-1", "0", "1", "응답 지연은 0 이상 60,000밀리초 이하여야 합니다."),
                Arguments.of("60001", "0", "1", "응답 지연은 0 이상 60,000밀리초 이하여야 합니다."),
                Arguments.of("0", "101", "1", "오류 비율은 0 이상 100 이하여야 합니다."),
                Arguments.of("0", "-1", "1", "오류 비율은 0 이상 100 이하여야 합니다."),
                Arguments.of("0", "50%", "1", "오류 비율에는 정수를 넣어야 합니다."),
                Arguments.of("0", "0", "0", "알림 중복 횟수는 1 이상 10 이하여야 합니다."),
                Arguments.of("0", "0", "11", "알림 중복 횟수는 1 이상 10 이하여야 합니다."),
                Arguments.of("0", "0", "", "알림 중복 횟수에는 정수를 넣어야 합니다."));
    }

    @Test
    void 값을_아예_보내지_않아도_안내_문구로_답한다() throws Exception {
        // when & then
        mockMvc.perform(post("/settings/speed")).andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/settings/faults")).andExpect(flash().attributeExists("error"));
    }
}
