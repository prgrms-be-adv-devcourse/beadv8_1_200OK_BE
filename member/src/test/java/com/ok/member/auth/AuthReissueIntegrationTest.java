package com.ok.member.auth;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.exception.RestApiException;
import com.ok.common.security.TokenClaims;
import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.dto.TokenReissueCommand;
import com.ok.member.auth.application.service.AuthService;
import com.ok.member.auth.application.service.RefreshTokenService;
import com.ok.member.auth.application.service.TokenHasher;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.auth.exception.AuthErrorCode;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰 재발급 API를 Controller → Service → Repository(MySQL)까지 검증한다.
 * 보안 필터를 적용해, 만료된 Access Token이 붙어 있어도 재발급이 막히지 않는지도 함께 확인한다.
 */
class AuthReissueIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    RefreshTokenJpaRepository refreshTokenRepository;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    AuthService authService;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    // 서비스가 만료를 판단하는 기준 시계. 테스트 데이터도 같은 시계로 만들어 시간대가 달라도 흔들리지 않게 한다
    @Autowired
    Clock clock;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 재발급하면_새_토큰_두_개를_반환하고_새_Refresh_Token은_해시로_저장된다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();

        // when
        ResultActions result = performReissue(refreshToken);

        // then
        String body = result.andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String newAccessToken = JsonPath.read(body, "$.accessToken");
        String newRefreshToken = JsonPath.read(body, "$.refreshToken");

        Jwt sut = jwtDecoder.decode(newAccessToken);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getSubject()).isEqualTo(String.valueOf(member.getId()));
            // 일반 회원은 구매자 권한으로 발급된다 (회원 정보를 다시 읽어 계산)
            softly.assertThat(sut.getClaimAsStringList(TokenClaims.ROLES)).containsExactly("BUYER");
            softly.assertThat(newRefreshToken).isNotEqualTo(refreshToken);
            softly.assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(newRefreshToken))).isPresent();
        });
    }

    @Test
    void 재발급하면_세션의_행을_새로_만들지_않고_같은_행의_토큰을_회전한다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();
        Long sessionId = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))
                .map(RefreshToken::getId)
                .orElseThrow();

        // when
        String body = performReissue(refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newRefreshToken = JsonPath.read(body, "$.refreshToken");
        String newerRefreshToken = JsonPath.read(performReissue(newRefreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");

        // then
        // 두 번 재발급해도 이 회원의 행은 로그인 때 만든 하나뿐이고, 행에는 마지막 토큰의 해시만 남는다
        List<RefreshToken> rows = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getMemberId().equals(member.getId()))
                .toList();
        assertThat(rows).hasSize(1);
        RefreshToken sut = rows.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isEqualTo(sessionId);
            softly.assertThat(sut.getTokenHash()).isEqualTo(TokenHasher.sha256Hex(newerRefreshToken));
            softly.assertThat(sut.getRotatedAt()).isNotNull();
            softly.assertThat(refreshTokenRepository.findIdByTokenHash(TokenHasher.sha256Hex(newRefreshToken)))
                    .isEmpty();
        });
    }

    @Test
    void 회전된_토큰으로_다시_재발급하면_401을_반환하고_세션의_토큰은_그대로다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();
        String newRefreshToken = JsonPath.read(performReissue(refreshToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.refreshToken");

        // when
        ResultActions result = performReissue(refreshToken);

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
        // 재사용 탐지는 아직 없으므로 지금 토큰은 계속 쓸 수 있다
        assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(newRefreshToken))).isPresent();
    }

    @Test
    void 사용한_Refresh_Token으로_다시_재발급하면_401을_반환한다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();
        performReissue(refreshToken).andExpect(status().isOk());

        // when
        ResultActions result = performReissue(refreshToken);

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 저장되지_않은_Refresh_Token이면_401을_반환한다() throws Exception {
        // when
        ResultActions result = performReissue("unknown-refresh-token");

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 만료된_Refresh_Token이면_401을_반환한다() throws Exception {
        // given
        // 서비스와 같은 시계로 하루 전에 만료된 토큰을 저장한다
        Member member = saveMember(MemberStatus.ACTIVE);
        String raw = "expired-refresh-token";
        refreshTokenRepository.saveAndFlush(RefreshToken.create(
                member.getId(), TokenHasher.sha256Hex(raw), LocalDateTime.now(clock).minusDays(1)));

        // when
        ResultActions result = performReissue(raw);

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @ParameterizedTest
    @MethodSource("재발급할_수_없는_회원_상태")
    void 재발급할_수_없는_상태의_회원은_거부되고_기존_Refresh_Token은_남는다(
            MemberStatus status, int expectedStatus, String expectedType) throws Exception {
        // given
        Member member = saveMember(status);
        String refreshToken = refreshTokenService.issue(member.getId()).value();

        // when
        ResultActions result = performReissue(refreshToken);

        // then
        result.andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.type", endsWith(expectedType)));
        // 실패하면 트랜잭션이 취소되어 토큰 회전도 되돌려진다
        RefreshToken sut = refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken)).orElse(null);
        assertThat(sut).isNotNull();
        assertThat(sut.getRotatedAt()).isNull();
    }

    static Stream<Arguments> 재발급할_수_없는_회원_상태() {
        return Stream.of(
                Arguments.of(MemberStatus.WITHDRAWN, 401, "/auth/invalid-refresh-token"),
                Arguments.of(MemberStatus.SUSPENDED, 403, "/auth/member-suspended")
        );
    }

    @Test
    void 만료된_Access_Token을_붙여_보내도_재발급이_처리된다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auth/reissue")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(member.getId(), "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)));

        // then
        result.andExpect(status().isOk());
    }

    @Test
    void 같은_Refresh_Token으로_동시에_재발급하면_하나만_성공한다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);
        String refreshToken = refreshTokenService.issue(member.getId()).value();
        int requestCount = 2;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        // 성공하면 새 Refresh Token, 실패하면 null
        Callable<String> reissue = () -> {
            ready.countDown();
            start.await();
            try {
                return authService.reissue(new TokenReissueCommand(refreshToken)).refreshToken();
            } catch (RestApiException e) {
                assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_REFRESH_TOKEN);
                return null;
            }
        };

        // when
        List<String> sut;
        try (ExecutorService executor = Executors.newFixedThreadPool(requestCount)) {
            List<Future<String>> futures = Stream.generate(() -> executor.submit(reissue))
                    .limit(requestCount)
                    .toList();
            ready.await();
            start.countDown();
            sut = futures.stream().map(AuthReissueIntegrationTest::get).toList();
        }

        // then
        List<String> issued = sut.stream().filter(Objects::nonNull).toList();
        assertThat(issued).hasSize(1);
        // 행을 지우지 않고 회전하므로, 진 쪽이 회전된 행을 다시 회전하지 않았는지 세션 행의 해시가 이긴 쪽의 새 토큰인 것으로 확인한다
        List<RefreshToken> sessions = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getMemberId().equals(member.getId()))
                .toList();
        assertThat(sessions).singleElement()
                .extracting(RefreshToken::getTokenHash)
                .isEqualTo(TokenHasher.sha256Hex(issued.getFirst()));
    }

    private Member saveMember(MemberStatus status) {
        Member member = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0));
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(member, "status", status);
        return memberRepository.saveAndFlush(member);
    }

    private ResultActions performReissue(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reissue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)));
    }

    // 문자열 포맷으로 만들면 따옴표·백슬래시가 들어간 값에서 JSON이 깨지므로 직렬화한다
    private String body(String refreshToken) {
        return jsonMapper.writeValueAsString(Map.of("refreshToken", refreshToken));
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
