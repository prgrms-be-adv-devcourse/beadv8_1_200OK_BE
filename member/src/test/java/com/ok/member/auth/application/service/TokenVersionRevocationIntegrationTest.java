package com.ok.member.auth.application.service;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.security.TokenVersionStore;
import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.dto.LoginCredential;
import com.ok.member.auth.application.dto.TokenResult;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.application.port.in.MemberPasswordChangeUseCase;
import com.ok.member.member.application.port.in.TokenVersionResyncUseCase;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호를 바꾸면 그 전에 발급된 Refresh Token(재발급)과 Access Token(보호된 API)이 거부되는지 실제 API, DB, Redis로 검증한다(#561).
 * 커밋 전 스냅샷을 읽은 로그인을 재현하려고 {@link AuthService}의 package-private 발급 메서드를 직접 불러, 이 패키지에 둔다.
 */
class TokenVersionRevocationIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_ID = "seoyeon88";
    private static final String PASSWORD = "password1!";
    private static final String NEW_PASSWORD = "newPassword1!";

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    RefreshTokenJpaRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AuthService authService;

    @Autowired
    MemberPasswordChangeUseCase memberPasswordChangeUseCase;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    TokenVersionResyncUseCase tokenVersionResyncUseCase;

    // 기록 확인은 키 형식에 묶이지 않도록 저장소 인터페이스로 한다. 키 문자열은 기록을 지울 때만 쓴다
    @Autowired
    TokenVersionStore tokenVersionStore;

    @Autowired
    TestAccessTokens tokens;

    private MockMvc mockMvc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberId = memberRepository.saveAndFlush(Member.createUser(LOGIN_ID, passwordEncoder.encode(PASSWORD),
                "seoyeon88@example.com", "이서연", "주말장보기", LocalDateTime.of(2026, 10, 19, 12, 0))).getId();
    }

    // Redis 컨테이너를 JVM 안의 다른 테스트와 공유하므로 이 테스트가 남긴 키를 지운다
    @AfterEach
    void tearDown() {
        redisTemplate.delete(tokenVersionKey());
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 비밀번호를_바꾸면_다른_기기의_Access_Token으로_보호된_API를_부를_때_401_invalid_token이다() throws Exception {
        // given
        Session phone = login(PASSWORD);
        Session laptop = login(PASSWORD);
        getMyInfo(laptop.accessToken()).andExpect(status().isOk());
        changePassword(phone.accessToken()).andExpect(status().isNoContent());

        // when
        ResultActions laptopResult = getMyInfo(laptop.accessToken());
        ResultActions phoneResult = getMyInfo(phone.accessToken());

        // then: 만료가 아니라 폐기라 재발급이 아닌 재로그인으로 보낸다
        laptopResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
        phoneResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
    }

    @Test
    void 배포_전에_발급되어_ver_클레임이_없는_Access_Token도_비밀번호를_바꾸면_401이다() throws Exception {
        // given: ver가 없는 토큰은 버전 0으로 본다
        String legacyBearer = tokens.bearer(memberId, "BUYER");
        mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, legacyBearer))
                .andExpect(status().isOk());
        changePassword(login(PASSWORD).accessToken()).andExpect(status().isNoContent());

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, legacyBearer));

        // then
        result.andExpect(status().isUnauthorized());
    }

    @Test
    void 비밀번호를_바꾼_뒤_새_비밀번호로_로그인해_받은_Access_Token은_통과한다() throws Exception {
        // given
        changePassword(login(PASSWORD).accessToken()).andExpect(status().isNoContent());
        Session after = login(NEW_PASSWORD);

        // when
        ResultActions result = getMyInfo(after.accessToken());

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void Redis의_기록이_사라져도_다시_채우면_옛_Access_Token이_다시_거부된다() throws Exception {
        // given: 장애 중 쓰기 실패나 재시작으로 기록이 빠져 옛 토큰이 통과하는 상태
        Session before = login(PASSWORD);
        changePassword(before.accessToken()).andExpect(status().isNoContent());
        redisTemplate.delete(tokenVersionKey());
        getMyInfo(before.accessToken()).andExpect(status().isOk());

        // when
        tokenVersionResyncUseCase.resync();

        // then: 반환 건수는 공유 DB의 다른 회원에 따라 달라지므로 이 회원의 기록만 본다
        assertThat(tokenVersionStore.findMinVersion(memberId)).hasValue(1L);
        getMyInfo(before.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void 다시_채우기가_옛_버전을_써도_최소_버전은_내려가지_않는다() throws Exception {
        // given: 비밀번호를 두 번 바꿔 최소 버전이 2다. 첫 변경 뒤에 받은 토큰(버전 1)도 두 번째 변경으로 폐기됐다
        Session first = login(PASSWORD);
        changePassword(first.accessToken()).andExpect(status().isNoContent());
        Session second = login(NEW_PASSWORD);
        changePassword(second.accessToken(), NEW_PASSWORD, "thirdPassword1!").andExpect(status().isNoContent());

        // when: 두 번째 변경 전에 DB를 읽은 다시 채우기나 첫 변경의 기록이 늦게 도착한 상황
        tokenVersionStore.saveMinVersion(memberId, 1L, Duration.ofMinutes(31));

        // then
        assertThat(tokenVersionStore.findMinVersion(memberId)).hasValue(2L);
        getMyInfo(second.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void 현재_비밀번호가_틀려_바꾸지_못하면_Redis에_기록이_남지_않고_기존_Access_Token이_통과한다() throws Exception {
        // given
        Session session = login(PASSWORD);

        // when
        mockMvc.perform(patch("/api/v1/members/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(
                                Map.of("currentPassword", "wrongPassword1!", "newPassword", NEW_PASSWORD))))
                .andExpect(status().isBadRequest());

        // then
        assertThat(tokenVersionStore.findMinVersion(memberId)).isEmpty();
        getMyInfo(session.accessToken()).andExpect(status().isOk());
    }

    @Test
    void 비밀번호를_바꾸면_다른_기기의_세션과_바꾼_기기의_세션_모두_재발급이_401이다() throws Exception {
        // given: 두 기기에서 로그인한 뒤 한 기기에서 비밀번호를 바꾼다
        Session phone = login(PASSWORD);
        Session laptop = login(PASSWORD);
        changePassword(phone.accessToken()).andExpect(status().isNoContent());

        // when
        ResultActions laptopResult = reissue(laptop.refreshToken());
        ResultActions phoneResult = reissue(phone.refreshToken());

        // then
        laptopResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
        phoneResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 비밀번호를_바꾸기_전에_재발급으로_회전한_세션도_바꾼_뒤에는_재발급이_401이다() throws Exception {
        // given: 회전할 때 세션의 버전이 지금 버전으로 바뀌면 안 된다. 바뀌면 폐기 전에 연 세션이 계속 살아난다
        Session session = login(PASSWORD);
        String rotatedRefreshToken = JsonPath.read(reissue(session.refreshToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");
        changePassword(session.accessToken()).andExpect(status().isNoContent());

        // when
        ResultActions result = reissue(rotatedRefreshToken);

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 비밀번호를_바꾼_뒤_새_비밀번호로_로그인한_세션은_재발급된다() throws Exception {
        // given
        Session before = login(PASSWORD);
        changePassword(before.accessToken()).andExpect(status().isNoContent());
        Session after = login(NEW_PASSWORD);

        // when
        ResultActions result = reissue(after.refreshToken());

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 비밀번호_변경이_커밋되기_전에_회원을_읽은_로그인의_Refresh_Token과_Access_Token이_모두_401이다() throws Exception {
        // given: 로그인 트랜잭션이 회원을 읽어 스냅샷을 잡은 뒤 멈추고, 그사이 비밀번호 변경이 커밋된다.
        // 로그인은 이어서 옛 버전으로 토큰을 만든다. 발급은 막지 못하지만 그 토큰들이 다음 요청에서 거부되어야 한다
        LoginCredential credential = authService.findLoginCredential(LOGIN_ID).orElseThrow();
        CountDownLatch memberRead = new CountDownLatch(1);
        CountDownLatch passwordChanged = new CountDownLatch(1);

        TokenResult staleLogin;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<TokenResult> login = executor.submit(() -> transactionTemplate.execute(status -> {
                authService.findLoginCredential(LOGIN_ID);
                memberRead.countDown();
                await(passwordChanged);
                return authService.issueLoginTokens(credential);
            }));
            assertThat(memberRead.await(10, TimeUnit.SECONDS)).isTrue();
            memberPasswordChangeUseCase.changePassword(memberId, new PasswordChangeCommand(PASSWORD, NEW_PASSWORD));
            passwordChanged.countDown();
            staleLogin = login.get(10, TimeUnit.SECONDS);
        }

        // when
        ResultActions accessResult = getMyInfo(staleLogin.accessToken());
        ResultActions refreshResult = reissue(staleLogin.refreshToken());

        // then
        accessResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/invalid-token")));
        refreshResult.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("비밀번호 변경을 기다리다 시간이 지났습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Session login(String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(Map.of("loginId", LOGIN_ID, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken"));
    }

    private ResultActions changePassword(String accessToken) throws Exception {
        return changePassword(accessToken, PASSWORD, NEW_PASSWORD);
    }

    private ResultActions changePassword(String accessToken, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(patch("/api/v1/members/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("currentPassword", currentPassword, "newPassword", newPassword))));
    }

    private ResultActions getMyInfo(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/v1/members/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken));
    }

    private String tokenVersionKey() {
        return "member:token-version:v1:" + memberId;
    }

    private record Session(String accessToken, String refreshToken) {
    }

    private ResultActions reissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

}
