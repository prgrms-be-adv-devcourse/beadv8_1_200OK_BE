package com.ok.carriersimulator.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.core.FaultSettings;
import com.ok.carriersimulator.core.Scenario;
import com.ok.carriersimulator.testsupport.MutableClock;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
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

// 로그인이 없는 화면이라, 개발자가 연 다른 웹 페이지가 이 서버로 폼을 보내 상태를 바꾸지 못하게 막는지 본다
@SimulatorWebTest
class CrossSiteGuardTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private CrossSiteGuard guard;
    @Autowired
    private CarrierSimulator simulator;
    @Autowired
    private MutableClock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(guard).build();
        simulator.clearAll();
        simulator.updateFaults(FaultSettings.none());
        simulator.createDummies(Scenario.NORMAL, "kr.cjlogistics", 2, null, clock.instant());
    }

    @ParameterizedTest
    @MethodSource("다른_사이트의_요청")
    void 다른_사이트가_보낸_폼은_거절하고_상태를_바꾸지_않는다(String header, String value) throws Exception {
        // when & then
        mockMvc.perform(post("/settings/clear-all").header("Host", "127.0.0.1:8090").header(header, value))
                .andExpect(status().isForbidden());
        assertThat(simulator.stats(clock.instant()).parcelCount()).isEqualTo(2);
    }

    static Stream<Arguments> 다른_사이트의_요청() {
        return Stream.of(
                Arguments.of("Origin", "https://evil.example"),
                Arguments.of("Origin", "http://127.0.0.1:9999"),
                Arguments.of("Origin", "null"),
                Arguments.of("Sec-Fetch-Site", "cross-site"),
                Arguments.of("Sec-Fetch-Site", "same-site"));
    }

    @ParameterizedTest
    @MethodSource("같은_사이트의_요청")
    void 같은_사이트의_폼과_도구의_요청은_받는다(String header, String value) throws Exception {
        // when & then
        mockMvc.perform(post("/settings/clear-all").header("Host", "127.0.0.1:8090").header(header, value))
                .andExpect(status().is3xxRedirection());
        assertThat(simulator.stats(clock.instant()).parcelCount()).isZero();
    }

    static Stream<Arguments> 같은_사이트의_요청() {
        return Stream.of(
                Arguments.of("Origin", "http://127.0.0.1:8090"),
                Arguments.of("Sec-Fetch-Site", "same-origin"),
                // 주소창에 직접 넣었거나 curl처럼 브라우저가 아닌 도구
                Arguments.of("Sec-Fetch-Site", "none"),
                Arguments.of("X-Not-A-Browser", "curl"));
    }

    @Test
    void 화면을_여는_요청과_택배사_API는_막지_않는다() throws Exception {
        // when & then: delivery가 부르는 API는 다른 출처에서 와도 받는다
        mockMvc.perform(get("/").header("Sec-Fetch-Site", "cross-site")).andExpect(status().isOk());
        mockMvc.perform(post("/oauth2/token").header("Origin", "https://evil.example")
                        .contentType("application/x-www-form-urlencoded")
                        .content("grant_type=client_credentials&client_id=a&client_secret=b"))
                .andExpect(status().isOk());
    }
}
