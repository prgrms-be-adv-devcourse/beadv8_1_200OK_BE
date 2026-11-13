package com.ok.carriersimulator.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.ParcelFilter;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.core.Scenario;
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
class ParcelControllerTest {

    private static final String CARRIER = "kr.cjlogistics";

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
        simulator.clearAll();
        simulator.updateFaults(FaultSettings.none());
    }

    @Test
    void 더미를_만들면_목록에_보이고_만든_번호를_돌려준다() throws Exception {
        // when
        mockMvc.perform(post("/parcels/dummies")
                        .param("scenario", "STUCK").param("carrierId", "kr.epost").param("count", "3")
                        .param("callbackUrl", "").param("refresh", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/parcels?refresh=5"))
                .andExpect(flash().attribute("message", "더미 송장 3건을 만들었습니다. (이동 멈춤)"))
                .andExpect(flash().attribute("createdNumbers", "903000000001\n903000000002\n903000000003"));

        // then
        mockMvc.perform(get("/parcels"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("total", 3L))
                .andExpect(content().string(containsString("903000000002")))
                .andExpect(content().string(containsString("kr.epost")))
                .andExpect(content().string(containsString("이동 멈춤")));
    }

    @Test
    void 받는_곳을_넣으면_등록_알림이_쌓인다() throws Exception {
        // when
        mockMvc.perform(post("/parcels/dummies")
                        .param("scenario", "NORMAL").param("carrierId", CARRIER).param("count", "4")
                        .param("callbackUrl", " http://localhost:8080/carrier-callback "))
                .andExpect(status().is3xxRedirection());

        // then
        assertThat(simulator.stats(clock.instant()).pendingCallbacks()).isEqualTo(4);
    }

    @Test
    void 만든_번호는_천_개까지만_보여_준다() throws Exception {
        // when & then
        mockMvc.perform(post("/parcels/dummies")
                        .param("scenario", "NORMAL").param("carrierId", CARRIER).param("count", "1200"))
                .andExpect(flash().attribute("createdHidden", 200));
    }

    @Test
    void 시나리오와_상태와_번호_앞자리로_거른다() throws Exception {
        // given
        simulator.createDummies(Scenario.NORMAL, CARRIER, 2, null, clock.instant());
        simulator.createDummies(Scenario.STUCK, CARRIER, 1, null, clock.instant());
        clock.advance(Duration.ofSeconds(100));

        // when & then
        mockMvc.perform(get("/parcels").param("scenario", "STUCK"))
                .andExpect(model().attribute("total", 1L))
                .andExpect(content().string(containsString("903000000003")))
                .andExpect(content().string(not(containsString("901000000001"))));
        mockMvc.perform(get("/parcels").param("status", "DELIVERED"))
                .andExpect(model().attribute("total", 2L));
        mockMvc.perform(get("/parcels").param("prefix", " 9010 "))
                .andExpect(model().attribute("total", 2L));
    }

    @Test
    void 쉰_건씩_쪽을_나눈다() throws Exception {
        // given
        simulator.createDummies(Scenario.NORMAL, CARRIER, 120, null, clock.instant());

        // when & then
        mockMvc.perform(get("/parcels"))
                .andExpect(model().attribute("totalPages", 3L))
                .andExpect(content().string(containsString("901000000050")))
                .andExpect(content().string(not(containsString("901000000051"))))
                .andExpect(content().string(containsString("href=\"/parcels?prefix=&amp;scenario=&amp;status=&amp;page=1\"")));
        mockMvc.perform(get("/parcels").param("page", "2"))
                .andExpect(content().string(containsString("901000000120")))
                .andExpect(model().attribute("nextLink", (Object) null));
    }

    @ParameterizedTest
    @MethodSource("틀린_조회_조건")
    void 조회_조건이_틀려도_화면은_열린다(String name, String value) throws Exception {
        // given
        simulator.createDummies(Scenario.NORMAL, CARRIER, 1, null, clock.instant());

        // when & then
        mockMvc.perform(get("/parcels").param(name, value))
                .andExpect(status().isOk());
    }

    static Stream<Arguments> 틀린_조회_조건() {
        return Stream.of(
                Arguments.of("scenario", "없는시나리오"),
                Arguments.of("status", "<script>"),
                Arguments.of("page", "-3"),
                Arguments.of("page", "abc"),
                Arguments.of("page", "99999999999999"),
                Arguments.of("prefix", "\"><b>"));
    }

    @Test
    void 번호_앞자리에_넣은_꺾쇠는_화면에_그대로_실리지_않는다() throws Exception {
        // when & then
        mockMvc.perform(get("/parcels").param("prefix", "\"><script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }

    @Test
    void 상세에_사건과_처음_가져간_시각이_보인다() throws Exception {
        // given: 집화(4초)가 일어난 3초 뒤에 조회해 간다
        ParcelKey key = new ParcelKey(CARRIER, "901000000001");
        simulator.track(key, clock.instant());
        clock.advance(Duration.ofSeconds(7));
        simulator.track(key, clock.instant());

        // when & then
        mockMvc.perform(get("/parcels/kr.cjlogistics/901000000001"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("집화")))
                .andExpect(content().string(containsString("3.0초")))
                .andExpect(content().string(containsString("등록이 없습니다")));
    }

    @Test
    void 등록이_있으면_상세에_받는_곳이_보인다() throws Exception {
        // given
        simulator.createDummies(Scenario.NORMAL, CARRIER, 1, "http://localhost:8080/carrier-callback", clock.instant());

        // when & then
        mockMvc.perform(get("/parcels/kr.cjlogistics/901000000001"))
                .andExpect(content().string(containsString("http://localhost:8080/carrier-callback")));
    }

    @Test
    void 없는_송장_상세는_404다() throws Exception {
        // when & then
        mockMvc.perform(get("/parcels/kr.cjlogistics/000"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @MethodSource("잘못된_더미_요청")
    void 더미_요청이_잘못되면_안내_문구가_보이고_송장은_생기지_않는다(String scenario, String carrierId, String count,
                                                String callbackUrl, String message) throws Exception {
        // when & then
        mockMvc.perform(post("/parcels/dummies")
                        .param("scenario", scenario).param("carrierId", carrierId).param("count", count)
                        .param("callbackUrl", callbackUrl))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/parcels"))
                .andExpect(flash().attribute("error", message));
        assertThat(simulator.parcels(ParcelFilter.all(), 0, 50, clock.instant()).total()).isZero();
    }

    static Stream<Arguments> 잘못된_더미_요청() {
        String countRange = "더미 송장 개수는 1 이상 100,000 이하여야 합니다.";
        return Stream.of(
                Arguments.of("NORMAL", CARRIER, "0", "", countRange),
                Arguments.of("NORMAL", CARRIER, "-5", "", countRange),
                Arguments.of("NORMAL", CARRIER, "100001", "", countRange),
                Arguments.of("NORMAL", CARRIER, "abc", "", "개수에는 정수를 넣어야 합니다."),
                Arguments.of("NORMAL", CARRIER, "", "", "개수에는 정수를 넣어야 합니다."),
                Arguments.of("NORMAL", CARRIER, "1.5", "", "개수에는 정수를 넣어야 합니다."),
                Arguments.of("NORMAL", CARRIER, "99999999999", "", "개수에는 정수를 넣어야 합니다."),
                Arguments.of("", CARRIER, "3", "", "시나리오를 골라야 합니다."),
                Arguments.of("없는시나리오", CARRIER, "3", "", "시나리오를 골라야 합니다."),
                Arguments.of("NORMAL", " ", "3", "", "택배사 값이 비어 있습니다."),
                Arguments.of("NORMAL", CARRIER, "3", "localhost:8080/callback", "받는 곳 주소는 http 또는 https 주소여야 합니다."),
                Arguments.of("NORMAL", CARRIER, "3", "javascript:alert(1)", "받는 곳 주소는 http 또는 https 주소여야 합니다."));
    }

    @Test
    void 값을_아예_보내지_않아도_안내_문구로_답한다() throws Exception {
        // when & then
        mockMvc.perform(post("/parcels/dummies"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
    }

    @ParameterizedTest
    @MethodSource("한_번만_보이는_내용")
    void 안내_문구나_만든_번호가_보이는_화면은_새로고침하지_않는다(String name, String value) throws Exception {
        // when & then: 새로고침하면 한 번만 보이는 내용이 사라져 번호를 복사할 수 없다
        mockMvc.perform(get("/parcels").param("refresh", "3").flashAttr(name, value).flashAttr("createdHidden", 0))
                .andExpect(content().string(containsString(value)))
                .andExpect(content().string(not(containsString("data-refresh"))));
        mockMvc.perform(get("/parcels").param("refresh", "3"))
                .andExpect(content().string(containsString("data-refresh=\"3\"")));
    }

    static Stream<Arguments> 한_번만_보이는_내용() {
        return Stream.of(
                Arguments.of("createdNumbers", "901000000001"),
                Arguments.of("message", "더미 송장 1건을 만들었습니다."),
                Arguments.of("error", "개수에는 정수를 넣어야 합니다."));
    }

    @Test
    void 안내_문구는_다음_화면에_보인다() throws Exception {
        // when & then
        mockMvc.perform(get("/parcels").flashAttr("error", "개수에는 정수를 넣어야 합니다."))
                .andExpect(content().string(containsString("class=\"flash error\"")))
                .andExpect(content().string(containsString("개수에는 정수를 넣어야 합니다.")));
    }
}
