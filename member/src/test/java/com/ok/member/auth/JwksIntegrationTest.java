package com.ok.member.auth;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwksIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 토큰_없이_검증_목록의_공개키를_모두_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/.well-known/jwks.json"));

        // then
        // 테스트 설정은 rs256-v1로 서명하고 rs256-v2는 공개만 한다. 서명하지 않는 키도 공개해야 키 교체 전에 검증 앱이 미리 받아 둔다
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[*].kid", containsInAnyOrder("rs256-v1", "rs256-v2")))
                .andExpect(jsonPath("$.keys[*].kty", everyItem(is("RSA"))))
                .andExpect(jsonPath("$.keys[*].alg", everyItem(is("RS256"))))
                .andExpect(jsonPath("$.keys[*].use", everyItem(is("sig"))));
    }

    @Test
    void 응답에_개인키_필드가_없다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/.well-known/jwks.json"));

        // then
        // RSA 개인키를 이루는 값들(RFC 7518 6.3.2). 하나라도 나가면 누구나 토큰을 위조할 수 있다
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("d")))))
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("p")))))
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("q")))))
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("dp")))))
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("dq")))))
                .andExpect(jsonPath("$.keys[*]", everyItem(not(hasKey("qi")))));
    }

    @Test
    void 검증_앱의_캐시_시간을_Cache_Control로_알린다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/.well-known/jwks.json"));

        // then
        // 테스트 설정에 jwt.jwk-set이 없으므로 기본값 5분(300초)이다
        result.andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300"));
    }

}
