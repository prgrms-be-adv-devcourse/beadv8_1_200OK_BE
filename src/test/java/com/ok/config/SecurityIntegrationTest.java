package com.ok.config;

import com.ok.common.security.CurrentMemberId;
import com.ok.common.security.TokenClaims;
import com.ok.member.auth.config.JwtConfig;
import com.ok.member.auth.config.JwtProperties;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
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

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtProperties jwtProperties;

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
    void 만료된_토큰을_보내도_회원가입_요청은_토큰을_무시하고_처리한다() throws Exception {
        // given
        // 형식은 맞지만 검증에 실패하는 본문을 보내, 토큰 검증(401 token-expired)이 아니라 요청 값 검증(422)까지 갔는지 확인한다.
        // 회원을 실제로 만들지 않아 다른 테스트에 데이터를 남기지 않는다.
        String body = """
            {
              "loginId": "",
              "password": "",
              "email": "",
              "name": "",
              "nickname": "",
              "termsAgreed": false,
              "privacyAgreed": false
            }
            """;

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/members/signup")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.type", endsWith("/validation-failed")));
    }

    @Test
    void 만료된_토큰을_보내도_재발급_요청은_토큰을_무시하고_처리한다() throws Exception {
        // given
        // 저장되지 않은 Refresh Token을 보내, 토큰 검증(401 token-expired)이 아니라 재발급 처리(401 invalid-refresh-token)까지 갔는지 확인한다
        String body = """
                {"refreshToken": "unknown-refresh-token"}
                """;

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auth/reissue")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 만료된_토큰을_보내도_상품_조회_요청은_토큰을_무시하고_처리한다() throws Exception {
        // given
        // 비로그인 화면이 만료 토큰 때문에 깨지지 않는지 확인한다. 토큰을 검증했다면 401 token-expired가 나온다
        String expiredToken = tokens.expiredBearer(MEMBER_ID, "BUYER");

        // when
        ResultActions categories = mockMvc.perform(get("/api/v1/products/categories")
                .header(HttpHeaders.AUTHORIZATION, expiredToken));
        ResultActions products = mockMvc.perform(get("/api/v1/products")
                .header(HttpHeaders.AUTHORIZATION, expiredToken));

        // then
        categories.andExpect(status().isOk());
        products.andExpect(status().isOk());
    }

    @Test
    void 만료된_토큰을_보내도_상품_상세_조회_요청은_토큰을_무시하고_처리한다() throws Exception {
        // given
        // 없는 상품을 조회해, 토큰 검증(401 token-expired)이 아니라 상품 조회(404)까지 갔는지 확인한다.
        // 상품 데이터를 만들지 않아도 상세 경로가 토큰 무시 목록과 맞는지 알 수 있다
        Long notExistingProductId = Long.MAX_VALUE;

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/products/{productId}", notExistingProductId)
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER")));

        // then
        result.andExpect(status().isNotFound());
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
    void 판매자이면서_구매자인_토큰은_판매자_API와_회원_API를_모두_호출할_수_있다() throws Exception {
        // given
        String token = tokens.bearer(MEMBER_ID, "BUYER", "SELLER");

        // when
        ResultActions sellerOnly = mockMvc.perform(get("/test/security/seller-only")
                .header(HttpHeaders.AUTHORIZATION, token));
        ResultActions me = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, token));

        // then
        sellerOnly.andExpect(status().isOk());
        me.andExpect(status().isOk());
    }

    @Test
    void 권한이_없는_토큰으로_역할_제한_API를_호출하면_403을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/seller-only")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID)));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("/forbidden")));
    }

    @Test
    void subject가_숫자가_아닌_토큰이면_invalid_token으로_401을_반환한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearerForSubject("not-a-number", "BUYER")));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
    }

    @Test
    void 회원_id를_쓰지_않는_API도_공개_API가_아니면_토큰_없이_호출할_때_401을_반환한다() throws Exception {
        // when: 기본 거부 전에는 회원 id(@CurrentMemberId)를 받지 않는 API가 토큰 없이 통과했다
        ResultActions result = mockMvc.perform(get("/test/security/no-identity"));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/unauthorized")));
    }

    @Test
    void 회원_id를_쓰지_않는_API도_토큰이_있으면_호출할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/test/security/no-identity")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(1L, "BUYER")));

        // then
        result.andExpect(status().isOk());
    }

    // 결과(없는 상품, 빈 본문의 요청 값 검증 실패 등)와 상관없이 보안 필터를 통과했는지만 본다.
    // POST는 빈 본문을 보내, 처리 결과로도 401이 날 수 있는 로그인·재발급이 요청 값 검증(422)에서 멈추게 한다
    @ParameterizedTest
    @MethodSource("공개_API")
    void 공개_API는_토큰_없이_호출할_수_있다(HttpMethod method, String path) throws Exception {
        // when
        ResultActions result = mockMvc.perform(publicApiRequest(method, path));

        // then
        result.andExpect(this::passedSecurity);
    }

    @ParameterizedTest
    @MethodSource("공개_API")
    void 공개_API는_만료된_토큰이_붙어도_토큰을_읽지_않고_처리한다(HttpMethod method, String path) throws Exception {
        // when
        ResultActions result = mockMvc.perform(publicApiRequest(method, path)
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(MEMBER_ID, "BUYER")));

        // then
        result.andExpect(this::passedSecurity);
    }

    // SecurityConfig.PUBLIC_ENDPOINTS와 같은 목록. 컨트롤러 경로를 따로 적어 두어, 설정 쪽 경로 오타를 잡는다
    static Stream<Arguments> 공개_API() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/v1/auth/login"),
                Arguments.of(HttpMethod.POST, "/api/v1/members/signup"),
                Arguments.of(HttpMethod.POST, "/api/v1/auth/reissue"),
                Arguments.of(HttpMethod.GET, "/api/v1/products"),
                Arguments.of(HttpMethod.GET, "/api/v1/products/999999"),
                Arguments.of(HttpMethod.GET, "/api/v1/products/categories"),
                Arguments.of(HttpMethod.GET, "/api/v1/products/999999/reviews"),
                Arguments.of(HttpMethod.POST, "/api/v1/shipments/fee-quotes")
        );
    }

    @Test
    void 개발_테스트_프로필에서는_API_문서를_토큰_없이_볼_수_있다() throws Exception {
        // when: 운영 프로필이 아니면 API 문서 경로를 로그인 없이 허용한다
        ResultActions result = mockMvc.perform(get("/v3/api-docs"));

        // then
        result.andExpect(this::passedSecurity);
    }

    @Test
    void 토큰이_필요_없는_API는_토큰_없이_호출할_수_있다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/v1/products/categories"));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 만료_시각이_없는_토큰은_만료가_아니라_invalid_token으로_401을_반환한다() throws Exception {
        // given
        // exp가 없는 토큰은 재발급으로 해결되지 않으므로 token-expired(재발급 유도)로 분류되면 안 된다.
        // 거부 원인("exp claim is not valid")은 응답이 아니라 인증 실패 debug 로그로 추적한다.
        String token = "Bearer " + encodeWithoutExpiry();

        // when
        ResultActions result = mockMvc.perform(get("/test/security/me")
                .header(HttpHeaders.AUTHORIZATION, token));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
    }

    @ParameterizedTest
    @MethodSource("loopback_주소")
    void 같은_컨테이너에서_온_내부_API_요청은_토큰_없이_허용한다(String remoteAddr) throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/internal/test/security/ping")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                }));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.reached").value(true));
    }

    static Stream<String> loopback_주소() {
        // Tomcat은 IPv6 loopback을 축약하지 않은 형태로 줄 수 있다
        return Stream.of("127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1");
    }

    @ParameterizedTest
    @MethodSource("외부_주소")
    void 다른_주소에서_온_내부_API_요청은_토큰이_없으면_401로_거부한다(String remoteAddr) throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/internal/test/security/ping")
                .with(request -> {
                    request.setRemoteAddr(remoteAddr);
                    return request;
                }));

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reached").doesNotExist());
    }

    static Stream<String> 외부_주소() {
        // nginx 컨테이너(docker 브리지), VPC 내부, 인터넷 주소
        return Stream.of("172.18.0.5", "10.1.1.10", "203.0.113.7", "2001:db8::1");
    }

    @Test
    void 다른_주소에서_온_내부_API_요청은_유효한_토큰이_있어도_403으로_거부한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/internal/test/security/ping")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER"))
                .with(request -> {
                    request.setRemoteAddr("172.18.0.5");
                    return request;
                }));

        // then
        result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", endsWith("/forbidden")));
    }

    @Test
    void X_Forwarded_For를_loopback으로_위조해도_내부_API_요청을_거부한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/internal/test/security/ping")
                .header("X-Forwarded-For", "127.0.0.1")
                .header("X-Real-IP", "127.0.0.1")
                .with(request -> {
                    request.setRemoteAddr("172.18.0.5");
                    return request;
                }));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 공개_API는_원격_주소와_상관없이_기존처럼_동작한다() throws Exception {
        // when
        ResultActions result = mockMvc.perform(get("/api/v1/products/categories")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }));

        // then
        result.andExpect(status().isOk());
    }

    /**
     * exp만 빠진, 나머지는 실제 발급과 같은 토큰을 만든다. TestAccessTokens는 항상 exp를 넣으므로 직접 만든다.
     */
    private String encodeWithoutExpiry() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(MEMBER_ID))
                .audience(List.of(jwtProperties.audience()))
                .issuedAt(Instant.now())
                .claim(TokenClaims.ROLES, List.of("BUYER"))
                .build();
        JwsHeader header = JwsHeader.with(JwtConfig.SIGNING_ALGORITHM)
                .type(JwtConfig.TOKEN_TYPE)
                .keyId(jwtProperties.keyId())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static MockHttpServletRequestBuilder publicApiRequest(HttpMethod method, String path) {
        MockHttpServletRequestBuilder builder = request(method, path);
        return method == HttpMethod.POST ? builder.contentType(MediaType.APPLICATION_JSON).content("{}") : builder;
    }

    // 보안 필터에서 막히면 401, 처리 중 예기치 못한 오류면 500이 난다. 둘 다 아니면 공개 API로서 통과한 것이다
    private void passedSecurity(MvcResult result) {
        assertThat(result.getResponse().getStatus())
                .as("보안 필터를 통과해 처리되어야 한다")
                .isNotEqualTo(401)
                .isLessThan(500);
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

        // 회원 id를 받지 않아도 공개 API가 아니면 로그인이 필요한지 확인하는 용도
        @GetMapping("/no-identity")
        public Map<String, String> noIdentity() {
            return Map.of("result", "ok");
        }

        @PreAuthorize("hasRole('SELLER')")
        @GetMapping("/seller-only")
        public Map<String, Long> sellerOnly(@CurrentMemberId Long memberId) {
            return Map.of("memberId", memberId);
        }

    }

    /**
     * 내부 API 접근 제한 검증용 컨트롤러. 실제 내부 API는 서비스·DB에 의존하므로, 보안 판단만 보려고 따로 둔다.
     */
    @RestController
    @RequestMapping("/internal/test/security")
    public static class InternalSecurityTestController {

        @GetMapping("/ping")
        public Map<String, Boolean> ping() {
            return Map.of("reached", true);
        }

    }

}
