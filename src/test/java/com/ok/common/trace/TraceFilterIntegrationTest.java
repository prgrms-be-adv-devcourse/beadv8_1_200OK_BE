package com.ok.common.trace;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.ok.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class TraceFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void 요청_헤더가_없으면_응답에_숫자_traceId를_담는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/actuator/health"));

        // then
        result.andExpect(status().isOk())
                .andExpect(header().string(TraceContext.HEADER_NAME, matchesPattern("[0-9]+")));
    }

    @Test
    void 유효한_traceId_헤더는_그대로_돌려준다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/actuator/health")
                .header(TraceContext.HEADER_NAME, "abc-1"));

        // then
        result.andExpect(header().string(TraceContext.HEADER_NAME, "abc-1"));
    }

    // 필터가 보안 필터보다 먼저 실행되는 근거: 보안이 거부한 응답에도 traceId가 있다
    @Test
    void 보안이_거부한_401_응답에도_traceId를_담는다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/actuator/env"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(header().exists(TraceContext.HEADER_NAME));
    }
}
