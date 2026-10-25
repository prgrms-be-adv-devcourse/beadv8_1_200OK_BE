package com.ok.common.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

/**
 * 컨테이너 헬스체크용 actuator가 health만, 상세 없이 노출하는지 검증한다.
 */
@AutoConfigureMockMvc
class ActuatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TestAccessTokens tokens;

    // 컨테이너 헬스체크가 토큰 없이 호출하므로, 공개 경로(SecurityConfig.PUBLIC_PATHS)로 열려 있어야 한다
    @Test
    void health는_상세_없이_상태만_응답한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/actuator/health"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void health_외의_actuator_엔드포인트는_노출하지_않는다() throws Exception {
        // when: 기본 거부라 토큰이 없으면 노출 여부를 보기 전에 401이 나므로, 토큰을 붙여 노출 여부만 본다
        ResultActions result = mockMvc.perform(get("/actuator/env")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "ADMIN")));

        // then
        result.andExpect(status().isNotFound());
    }

    @Test
    void health_외의_actuator_경로는_토큰_없이_호출하면_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/actuator/env"));

        // then: 공개 경로는 /actuator/health 하나뿐이다
        result.andExpect(status().isUnauthorized());
    }

}
