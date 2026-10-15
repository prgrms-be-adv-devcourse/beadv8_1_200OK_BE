package com.ok.config;

import com.ok.common.security.CurrentMemberId;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * 보안 필터의 토큰 검증, {@code @CurrentMemberId}, 401·403 응답 형식을 검증한다.
 * 실제 모듈 API는 아직 토큰 방식으로 전환되지 않았으므로, 테스트 전용 컨트롤러({@link SecurityTestController})로 확인한다.
 */
class SecurityIntegrationTest extends AbstractIntegrationTest {

    private static final Long MEMBER_ID = 1L;

    @Autowired
    WebApplicationContext context;

    @Autowired
    TestAccessTokens tokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 보안 필터를 적용해야 토큰 검증과 401·403 처리가 실제와 같게 동작한다
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void 유효한_토큰이면_토큰의_회원_id로_식별한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID));
    }

    @Test
    void X_Member_Id_헤더로_다른_회원_id를_보내도_토큰의_회원으로_식별한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                .header("X-Member-Id", 999L));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(MEMBER_ID));
    }

    @Test
    void 토큰_없이_회원_id가_필요한_API를_호출하면_401과_Bearer_인증_요구를_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type", endsWith("/unauthorized")));
    }

    @Test
    void 만료된_토큰이면_재발급할_수_있도록_token_expired로_구분해_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER")));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error_description")))
                .andExpect(jsonPath("$.type", endsWith("/token-expired")));
    }

    @Test
    void 형식이_잘못된_토큰이면_invalid_token으로_구분해_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
    }

    @Test
    void 만료된_토큰을_보내도_로그인_요청은_토큰을_무시하고_처리한다() throws Exception {
        // given
        // 존재하지 않는 회원으로 로그인해, 토큰 검증(401 token-expired)이 아니라 로그인 처리(401 invalid-credentials)까지 갔는지 확인한다
        String body = """
            {"loginId": "nobody01", "password": "password1!"}
            """;

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auth/login")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-credentials")));
    }

    @Test
    void 역할이_맞으면_역할_제한_API를_호출할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/seller-only")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "SELLER")));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 역할이_맞지_않으면_403을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/seller-only")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("/forbidden")));
    }

    @Test
    void 토큰_없이_역할_제한_API를_호출하면_403이_아니라_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/seller-only"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/unauthorized")));
    }

    @Test
    void 토큰이_필요_없는_API는_토큰_없이_호출할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/v1/products/categories"));

        // then
        result.andExpect(status().isOk());
    }

    /**
     * 보안 동작 검증용 컨트롤러. 테스트 소스에만 있어 실제 애플리케이션에는 등록되지 않는다.
     * {@code @PreAuthorize} 프록시가 적용되도록 public으로 둔다.
     */
    @RestController
    @RequestMapping("/test/security")
    public static class SecurityTestController {

        @GetMapping("/me")
        public Map<String, Long> me(@CurrentMemberId Long memberId) {
            return Map.of("memberId", memberId);
        }

        @PreAuthorize("hasRole('SELLER')")
        @GetMapping("/seller-only")
        public Map<String, Long> sellerOnly(@CurrentMemberId Long memberId) {
            return Map.of("memberId", memberId);
        }

    }

}
