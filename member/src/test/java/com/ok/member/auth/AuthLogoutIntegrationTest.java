package com.ok.member.auth;

import com.ok.member.auth.adapter.out.persistence.RefreshTokenJpaRepository;
import com.ok.member.auth.application.service.RefreshTokenService;
import com.ok.member.auth.application.service.TokenHasher;
import com.ok.member.auth.domain.RefreshToken;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.domain.Member;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 로그아웃 API를 Controller → Service → Repository(MySQL)까지 검증한다.
 * 보안 필터를 적용해, Access Token 없이 또는 만료된 Access Token을 붙여도 로그아웃이 막히지 않는지도 함께 확인한다.
 */
class AuthLogoutIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    RefreshTokenJpaRepository refreshTokenRepository;

    @Autowired
    RefreshTokenService refreshTokenService;

    @Autowired
    TestAccessTokens tokens;

    @Autowired
    JsonMapper jsonMapper;

    // 서비스가 만료를 판단하는 기준 시계. 테스트 데이터도 같은 시계로 만든다
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
    void 로그아웃하면_204를_반환하고_그_세션의_행을_지운다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = refreshTokenService.issue(member.getId(), 0L).value();

        // when
        ResultActions result = performLogout(refreshToken);

        // then
        result.andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))).isEmpty();
    }

    @Test
    void 로그아웃한_Refresh_Token으로_재발급하면_401을_반환한다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = refreshTokenService.issue(member.getId(), 0L).value();
        performLogout(refreshToken).andExpect(status().isNoContent());

        // when
        ResultActions result = performReissue(refreshToken);

        // then
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type", endsWith("/auth/invalid-refresh-token")));
    }

    @Test
    void 한_기기에서_로그아웃해도_같은_회원의_다른_기기_세션은_재발급할_수_있다() throws Exception {
        // given
        Member member = saveMember();
        String pc = refreshTokenService.issue(member.getId(), 0L).value();
        String phone = refreshTokenService.issue(member.getId(), 0L).value();

        // when
        performLogout(pc).andExpect(status().isNoContent());

        // then
        performReissue(phone).andExpect(status().isOk());
        long sessions = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getMemberId().equals(member.getId()))
                .count();
        assertThat(sessions).isEqualTo(1);
    }

    @Test
    void 저장되지_않은_Refresh_Token으로_로그아웃해도_204를_반환한다() throws Exception {
        // when
        ResultActions result = performLogout("unknown-refresh-token");

        // then
        result.andExpect(status().isNoContent());
    }

    @Test
    void 만료된_Refresh_Token으로_로그아웃하면_204를_반환하고_행은_정리_스케줄러에_맡긴다() throws Exception {
        // given
        // 서비스와 같은 시계로 하루 전에 만료된 토큰을 저장한다
        Member member = saveMember();
        String raw = "expired-refresh-token";
        refreshTokenRepository.saveAndFlush(RefreshToken.create(
                member.getId(), TokenHasher.sha256Hex(raw), LocalDateTime.now(clock).minusDays(1), 0L));

        // when
        ResultActions result = performLogout(raw);

        // then
        result.andExpect(status().isNoContent());
        // 정리와 같은 행을 함께 지우면 교착 상태가 생길 수 있어 만료된 행은 지우지 않는다 (RefreshTokenService.revoke 참고)
        assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(raw))).isPresent();
    }

    @Test
    void 같은_Refresh_Token으로_두_번_로그아웃해도_모두_204를_반환한다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = refreshTokenService.issue(member.getId(), 0L).value();
        performLogout(refreshToken).andExpect(status().isNoContent());

        // when
        ResultActions result = performLogout(refreshToken);

        // then
        result.andExpect(status().isNoContent());
    }

    @Test
    void 만료된_Access_Token을_붙여_보내도_로그아웃이_처리된다() throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = refreshTokenService.issue(member.getId(), 0L).value();

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, tokens.expiredBearer(member.getId(), "BUYER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)));

        // then
        result.andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("형식이_틀린_로그아웃_요청")
    void 형식이_틀린_요청이면_422를_반환하고_세션은_남는다(String requestBody) throws Exception {
        // given
        Member member = saveMember();
        String refreshToken = refreshTokenService.issue(member.getId(), 0L).value();

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody));

        // then
        result.andExpect(status().is(422));
        assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))).isPresent();
    }

    // 발급하는 토큰은 43자라 44자부터 형식 오류다
    static Stream<String> 형식이_틀린_로그아웃_요청() {
        return Stream.of(
                "{}",
                "{\"refreshToken\": null}",
                "{\"refreshToken\": \"\"}",
                "{\"refreshToken\": \"   \"}",
                "{\"refreshToken\": \"" + "a".repeat(44) + "\"}"
        );
    }

    private Member saveMember() {
        Member member = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0));
        return memberRepository.saveAndFlush(member);
    }

    private ResultActions performLogout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(refreshToken)));
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

}
