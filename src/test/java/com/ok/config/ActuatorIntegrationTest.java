package com.ok.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ok.testsupport.AbstractIntegrationTest;

/**
 * 컨테이너 헬스체크용 actuator가 health만, 상세 없이 노출하는지 검증한다.
 */
@AutoConfigureMockMvc
class ActuatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

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
        // when
        ResultActions result = mockMvc.perform(get("/actuator/env"));

        // then
        result.andExpect(status().isNotFound());
    }

}
