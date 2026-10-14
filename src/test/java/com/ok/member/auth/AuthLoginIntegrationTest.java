package com.ok.member.auth;

import com.jayway.jsonpath.JsonPath;
import com.ok.member.auth.repository.RefreshTokenRepository;
import com.ok.member.auth.service.AccessTokenProvider;
import com.ok.member.auth.service.TokenHasher;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthLoginIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_ID = "tester01";
    private static final String PASSWORD = "password1!";

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    RefreshTokenRepository refreshTokenRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 아이디와_비밀번호가_맞으면_200과_토큰을_반환하고_Refresh_Token은_해시로_저장된다() throws Exception {
        // given
        Member member = saveMember(MemberStatus.ACTIVE);

        // when
        MvcResult result = performLogin(LOGIN_ID, PASSWORD)
                .andExpect(status().isOk())
                .andReturn();

        // then
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String accessToken = JsonPath.read(body, "$.accessToken");
        String refreshToken = JsonPath.read(body, "$.refreshToken");
        Jwt sut = jwtDecoder.decode(accessToken);

        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat((String) JsonPath.read(body, "$.tokenType")).isEqualTo("Bearer");
            softly.assertThat(sut.getSubject()).isEqualTo(String.valueOf(member.getId()));
            softly.assertThat(sut.getClaimAsString(AccessTokenProvider.ROLE_CLAIM)).isEqualTo("BUYER");
            softly.assertThat((String) JsonPath.read(body, "$.accessTokenExpiresAt")).isNotBlank();
            softly.assertThat((String) JsonPath.read(body, "$.refreshTokenExpiresAt")).isNotBlank();
            softly.assertThat(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken)))
                    .hasValueSatisfying(saved -> assertThat(saved.getMemberId()).isEqualTo(member.getId()));
        });
    }

    @Test
    void 대소문자만_다른_아이디로도_로그인할_수_있다() throws Exception {
        // given
        saveMember(MemberStatus.ACTIVE);

        // when
        ResultActions result = performLogin(" TESTER01 ", PASSWORD);

        // then
        result.andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("틀린_로그인_정보")
    void 아이디가_없거나_비밀번호가_틀리면_401을_반환하고_토큰을_저장하지_않는다(String loginId, String password)
            throws Exception {
        // given
        saveMember(MemberStatus.ACTIVE);

        // when
        ResultActions result = performLogin(loginId, password);

        // then
        result.andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.count()).isZero();
    }

    static Stream<Arguments> 틀린_로그인_정보() {
        return Stream.of(
                Arguments.of("unknown01", PASSWORD),
                Arguments.of(LOGIN_ID, "wrong-pass1!")
        );
    }

    @Test
    void 탈퇴한_회원은_비밀번호가_맞아도_401을_반환한다() throws Exception {
        // given
        saveMember(MemberStatus.WITHDRAWN);

        // when
        ResultActions result = performLogin(LOGIN_ID, PASSWORD);

        // then
        result.andExpect(status().isUnauthorized());
        assertThat(refreshTokenRepository.count()).isZero();
    }

    @Test
    void 정지된_회원은_403을_반환한다() throws Exception {
        // given
        saveMember(MemberStatus.SUSPENDED);

        // when
        ResultActions result = performLogin(LOGIN_ID, PASSWORD);

        // then
        result.andExpect(status().isForbidden());
    }

    @Test
    void 아이디와_비밀번호가_비어_있으면_422와_필드별_오류를_반환한다() throws Exception {
        // when
        ResultActions result = performLogin(" ", "");

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.errors[*].path", hasItem("loginId")))
                .andExpect(jsonPath("$.errors[*].path", hasItem("password")));
    }

    private Member saveMember(MemberStatus status) {
        Member member = Member.createBuyer(LOGIN_ID, passwordEncoder.encode(PASSWORD), "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 1, 12, 0));
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(member, "status", status);
        return memberRepository.saveAndFlush(member);
    }

    private ResultActions performLogin(String loginId, String password) throws Exception {
        // 문자열 포맷으로 만들면 따옴표·백슬래시가 들어간 값에서 JSON이 깨지므로 직렬화한다.
        // LoginRequest를 직렬화하면 생성자에서 아이디가 미리 정규화되어 HTTP 경로의 정규화를 검증할 수 없으므로 Map을 쓴다.
        String body = jsonMapper.writeValueAsString(Map.of("loginId", loginId, "password", password));
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

}
