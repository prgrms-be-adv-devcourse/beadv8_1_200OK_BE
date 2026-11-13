package com.ok.carriersimulator.web.admin;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.ParcelKey;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SimulatorWebTest
class DashboardControllerTest {

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
    void 현황_화면이_열린다() throws Exception {
        // when & then
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("받은 조회 (누적)")))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("송장 상태 분포")))
                .andExpect(content().string(not(containsString("http-equiv=\"refresh\""))));
    }

    @Test
    void 조회한_만큼_숫자와_막대가_늘어난다() throws Exception {
        // given: 정상 2건과 없는 송장 1건을 조회한다
        simulator.track(new ParcelKey("kr.cjlogistics", "901000000001"), clock.instant());
        simulator.track(new ParcelKey("kr.cjlogistics", "901000000002"), clock.instant());
        simulator.track(new ParcelKey("kr.cjlogistics", "904000000001"), clock.instant());

        // when & then
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalQueries", 3L))
                .andExpect(model().attribute("parcelCount", 3L))
                .andExpect(content().string(containsString("class=\"track_ok\"")))
                .andExpect(content().string(containsString("class=\"track_not_found\"")))
                .andExpect(content().string(containsString("최대 3건")));
    }

    @Test
    void 직전_10초의_초당_조회_수를_보여_준다() throws Exception {
        // given: 한 구간에 20번 조회하고 다음 구간으로 넘어간다
        clock.advance(Duration.ofSeconds(10 - clock.instant().getEpochSecond() % 10));
        for (int i = 0; i < 20; i++) {
            simulator.track(new ParcelKey("kr.cjlogistics", "901000000001"), clock.instant());
        }
        clock.advance(Duration.ofSeconds(10));

        // when & then
        mockMvc.perform(get("/"))
                .andExpect(model().attribute("queriesPerSecond", "2.0"));
    }

    @ParameterizedTest
    @MethodSource("새로고침_간격")
    void 새로고침_간격을_고르면_meta가_들어가고_메뉴_링크가_유지한다(String refresh) throws Exception {
        // when & then
        mockMvc.perform(get("/").param("refresh", refresh))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http-equiv=\"refresh\" content=\"" + refresh + "\"")))
                .andExpect(content().string(containsString("href=\"/parcels?refresh=" + refresh + "\"")));
    }

    static Stream<String> 새로고침_간격() {
        return Stream.of("3", "5", "10");
    }

    @ParameterizedTest
    @MethodSource("고를_수_없는_간격")
    void 고를_수_없는_새로고침_값은_끔으로_본다(String refresh) throws Exception {
        // when & then
        mockMvc.perform(get("/").param("refresh", refresh))
                .andExpect(status().isOk())
                .andExpect(model().attribute("refresh", 0))
                .andExpect(content().string(not(containsString("http-equiv=\"refresh\""))));
    }

    static Stream<String> 고를_수_없는_간격() {
        return Stream.of("0", "1", "-5", "abc", "", "<script>");
    }
}
