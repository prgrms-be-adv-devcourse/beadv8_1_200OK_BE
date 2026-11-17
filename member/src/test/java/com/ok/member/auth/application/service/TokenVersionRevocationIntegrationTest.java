package com.ok.member.auth.application.service;

import com.jayway.jsonpath.JsonPath;
import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.dto.LoginCredential;
import com.ok.member.auth.application.dto.TokenResult;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.application.port.in.MemberPasswordChangeUseCase;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호를 바꾸면 그 전에 연 세션의 Refresh Token이 재발급에서 거부되는지 실제 API와 DB로 검증한다(#561).
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

    private MockMvc mockMvc;

    private Long memberId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        memberId = memberRepository.saveAndFlush(Member.createUser(LOGIN_ID, passwordEncoder.encode(PASSWORD),
                "seoyeon88@example.com", "이서연", "주말장보기", LocalDateTime.of(2026, 10, 19, 12, 0))).getId();
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
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
    void 비밀번호_변경이_커밋되기_전에_회원을_읽은_로그인의_세션은_재발급이_401이다() throws Exception {
        // given: 로그인 트랜잭션이 회원을 읽어 스냅샷을 잡은 뒤 멈추고, 그사이 비밀번호 변경이 커밋된다.
        // 로그인은 이어서 옛 버전으로 세션을 저장한다. 발급은 막지 못하지만 그 세션이 다음 재발급에서 거부되어야 한다
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
        ResultActions result = reissue(staleLogin.refreshToken());

        // then
        result.andExpect(status().isUnauthorized())
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
        return mockMvc.perform(patch("/api/v1/members/me/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))));
    }

    private record Session(String accessToken, String refreshToken) {
    }

    private ResultActions reissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

}
