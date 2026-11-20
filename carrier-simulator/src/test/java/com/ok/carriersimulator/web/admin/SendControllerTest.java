package com.ok.carriersimulator.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ok.carriersimulator.core.CallbackAttempt;
import com.ok.carriersimulator.core.CallbackOutcome;
import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.DispatchSettings;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.StatusCode;
import com.ok.carriersimulator.core.TrackResult;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.context.WebApplicationContext;

@SimulatorWebTest
class SendControllerTest {

    private static final String CARRIER = "kr.cjlogistics";
    private static final String URL = "http://localhost:8080/carrier-callback";
    private static final DispatchSettings DEFAULT_DISPATCH = new DispatchSettings(Duration.ofSeconds(1), 100, 10, false);

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

    private static MultiValueMap<String, String> manualForm(String numbers, String status, String times, String url) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("carrierId", CARRIER);
        form.add("trackingNumbers", numbers);
        form.add("status", status);
        form.add("times", times);
        form.add("callbackUrl", url);
        return form;
    }

    @Test
    void 보내기_화면이_열린다() throws Exception {
        // when & then
        mockMvc.perform(get("/send"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("보내는 속도")))
                .andExpect(content().string(containsString("송장 번호를 지정해서 보내기")))
                .andExpect(content().string(containsString("보낸 알림이 없습니다.")))
                .andExpect(model().attribute("intervalSeconds", "1"))
                .andExpect(model().attribute("batchSize", 100))
                .andExpect(model().attribute("concurrency", 10));
    }

    @Test
    void 속도를_바꾸면_설정에_반영된다() throws Exception {
        // when
        mockMvc.perform(post("/send/settings")
                        .param("intervalSeconds", "0.5").param("batchSize", "250").param("concurrency", "20")
                        .param("refresh", "3"))
                .andExpect(redirectedUrl("/send?refresh=3"))
                .andExpect(flash().attribute("message", "보내는 속도를 바꿨습니다."));

        // then
        DispatchSettings sut = simulator.dispatch();
        assertSoftly(softly -> {
            softly.assertThat(sut.interval()).isEqualTo(Duration.ofMillis(500));
            softly.assertThat(sut.batchSize()).isEqualTo(250);
            softly.assertThat(sut.concurrency()).isEqualTo(20);
            softly.assertThat(sut.paused()).isFalse();
        });
        mockMvc.perform(get("/send")).andExpect(model().attribute("intervalSeconds", "0.5"));
    }

    @Test
    void 멈추면_설정이_멈춤이_되고_속도를_바꿔도_멈춤이_유지된다() throws Exception {
        // when
        mockMvc.perform(post("/send/pause")).andExpect(redirectedUrl("/send"));
        mockMvc.perform(post("/send/settings")
                .param("intervalSeconds", "2").param("batchSize", "10").param("concurrency", "1"));
        boolean pausedAfterUpdate = simulator.dispatch().paused();
        mockMvc.perform(get("/send")).andExpect(content().string(containsString("멈춤 상태입니다.")));
        mockMvc.perform(post("/send/resume"));

        // then
        assertSoftly(softly -> {
            softly.assertThat(pausedAfterUpdate).isTrue();
            softly.assertThat(simulator.dispatch().paused()).isFalse();
            softly.assertThat(simulator.dispatch().batchSize()).isEqualTo(10);
        });
    }

    @Test
    void 번호를_지정해_보내면_대기_줄에_쌓이고_지정한_상태로_조회된다() throws Exception {
        // when
        mockMvc.perform(post("/send/manual").params(manualForm("111111111111\n222222222222\n333333333333",
                        "DELIVERED", "2", URL)))
                .andExpect(redirectedUrl("/send"))
                .andExpect(flash().attribute("message", "송장 3건의 알림 6건을 대기 줄에 넣었습니다."));

        // then
        TrackResult result = simulator.track(new ParcelKey(CARRIER, "222222222222"), clock.instant());
        assertSoftly(softly -> {
            softly.assertThat(simulator.stats(clock.instant()).pendingCallbacks()).isEqualTo(6);
            softly.assertThat(result).isInstanceOf(TrackResult.Found.class);
            softly.assertThat(((TrackResult.Found) result).lastEvent().status()).isEqualTo(StatusCode.DELIVERED);
        });
        mockMvc.perform(get("/send")).andExpect(model().attribute("pendingCallbacks", 6L));
    }

    @Test
    void 빈_줄과_공백과_같은_번호는_한_번만_센다() throws Exception {
        // when
        mockMvc.perform(post("/send/manual").params(manualForm("  111111111111  \r\n\r\n   \n222222222222\n111111111111\n",
                        "IN_TRANSIT", "1", URL)))
                .andExpect(flash().attribute("message", "송장 2건의 알림 2건을 대기 줄에 넣었습니다."));

        // then
        assertThat(simulator.stats(clock.instant()).parcelCount()).isEqualTo(2);
    }

    @Test
    void 보낸_기록을_번호_앞자리로_찾고_성공과_실패를_보여_준다() throws Exception {
        // given: 한 건은 성공, 한 건은 시간 초과
        simulator.sendManual(List.of(new ParcelKey(CARRIER, "111111111111"), new ParcelKey(CARRIER, "222222222222")),
                StatusCode.DELIVERED, 1, URL, clock.instant());
        List<CallbackAttempt> attempts = simulator.pollDueCallbacks(clock.instant());
        simulator.completeCallback(attempts.get(0), CallbackOutcome.responded(202, 40), clock.instant());
        simulator.completeCallback(attempts.get(1), CallbackOutcome.failed("시간 초과", 1000), clock.instant());

        // when & then
        mockMvc.perform(get("/send"))
                .andExpect(model().attribute("sentCount", 2L))
                .andExpect(model().attribute("okCount", 1L))
                .andExpect(model().attribute("failCount", 1L))
                .andExpect(model().attribute("averageMillis", "520ms"))
                .andExpect(content().string(containsString("111111111111")))
                .andExpect(content().string(containsString("시간 초과")))
                .andExpect(content().string(containsString("class=\"callback_ok\"")))
                .andExpect(content().string(containsString("class=\"callback_fail\"")));
        mockMvc.perform(get("/send").param("prefix", "2222"))
                .andExpect(content().string(containsString("222222222222")))
                .andExpect(content().string(not(containsString("111111111111"))));
    }

    @ParameterizedTest
    @MethodSource("잘못된_속도")
    void 속도_값이_잘못되면_안내_문구가_보이고_설정은_그대로다(String interval, String batchSize, String concurrency,
                                             String message) throws Exception {
        // when & then
        mockMvc.perform(post("/send/settings")
                        .param("intervalSeconds", interval).param("batchSize", batchSize).param("concurrency", concurrency))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", message));
        assertThat(simulator.dispatch()).isEqualTo(DEFAULT_DISPATCH);
    }

    static Stream<Arguments> 잘못된_속도() {
        String intervalRange = "보내는 간격은 0.1초 이상 3,600초 이하여야 합니다.";
        return Stream.of(
                Arguments.of("0", "100", "10", intervalRange),
                Arguments.of("0.05", "100", "10", intervalRange),
                Arguments.of("-1", "100", "10", intervalRange),
                Arguments.of("3601", "100", "10", intervalRange),
                Arguments.of("NaN", "100", "10", intervalRange),
                Arguments.of("abc", "100", "10", "보내는 간격에는 숫자를 넣어야 합니다."),
                Arguments.of("", "100", "10", "보내는 간격에는 숫자를 넣어야 합니다."),
                Arguments.of("1", "abc", "10", "한 번에 보내는 개수에는 정수를 넣어야 합니다."),
                Arguments.of("1", "0", "10", "한 번에 보내는 개수는 1 이상 10,000 이하여야 합니다."),
                Arguments.of("1", "10001", "10", "한 번에 보내는 개수는 1 이상 10,000 이하여야 합니다."),
                Arguments.of("1", "100", "-1", "동시에 보내는 수는 1 이상 200 이하여야 합니다."),
                Arguments.of("1", "100", "201", "동시에 보내는 수는 1 이상 200 이하여야 합니다."));
    }

    @ParameterizedTest
    @MethodSource("잘못된_지정_전송")
    void 지정_전송_값이_잘못되면_안내_문구가_보이고_아무것도_보내지_않는다(String numbers, String status, String times, String url,
                                                  String message) throws Exception {
        // when & then
        mockMvc.perform(post("/send/manual").params(manualForm(numbers, status, times, url)))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", message));
        assertSoftly(softly -> {
            softly.assertThat(simulator.stats(clock.instant()).pendingCallbacks()).isZero();
            softly.assertThat(simulator.stats(clock.instant()).parcelCount()).isZero();
        });
    }

    static Stream<Arguments> 잘못된_지정_전송() {
        String noNumbers = "송장 번호를 한 줄에 하나씩 넣어야 합니다.";
        return Stream.of(
                Arguments.of("", "DELIVERED", "1", URL, noNumbers),
                Arguments.of(" \n \n", "DELIVERED", "1", URL, noNumbers),
                Arguments.of("111111111111", "", "1", URL, "상태를 골라야 합니다."),
                Arguments.of("111111111111", "없는상태", "1", URL, "상태를 골라야 합니다."),
                Arguments.of("111111111111", "DELIVERED", "0", URL, "보낼 횟수는 1 이상 100 이하여야 합니다."),
                Arguments.of("111111111111", "DELIVERED", "101", URL, "보낼 횟수는 1 이상 100 이하여야 합니다."),
                Arguments.of("111111111111", "DELIVERED", "두번", URL, "보낼 횟수에는 정수를 넣어야 합니다."),
                Arguments.of("111111111111", "DELIVERED", "1", "ftp://example.test",
                        "받는 곳 주소는 http 또는 https 주소여야 합니다."),
                // 받는 곳도 비우고 등록도 없는 송장
                Arguments.of("111111111111", "DELIVERED", "1", "",
                        "받는 곳 주소를 넣지 않았는데 알림 등록이 없는 송장입니다: 111111111111"));
    }

    @Test
    void 번호가_천_개를_넘으면_안내_문구가_보인다() throws Exception {
        // given
        StringBuilder numbers = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            numbers.append(100_000_000_000L + i).append('\n');
        }

        // when & then
        mockMvc.perform(post("/send/manual").params(manualForm(numbers.toString(), "DELIVERED", "1", URL)))
                .andExpect(flash().attribute("error", "송장 번호는 1개 이상 1,000개 이하로 넣어야 합니다."));
    }

    @Test
    void 값을_아예_보내지_않아도_안내_문구로_답한다() throws Exception {
        // when & then
        mockMvc.perform(post("/send/settings")).andExpect(flash().attributeExists("error"));
        mockMvc.perform(post("/send/manual")).andExpect(flash().attributeExists("error"));
    }
}
