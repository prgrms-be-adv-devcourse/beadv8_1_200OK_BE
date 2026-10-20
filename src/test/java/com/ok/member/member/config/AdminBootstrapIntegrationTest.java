package com.ok.member.member.config;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.security.TokenClaims;
import com.ok.member.auth.repository.RefreshTokenRepository;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberType;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.AdminAccountService;
import com.ok.member.member.service.dto.AdminAccountCommand;
import com.ok.member.member.service.dto.AdminAccountCreation;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 DB와 로그인 API로 최초 관리자 계정을 확인한다.
// 테스트 설정(application-test.yml)이 .env와 상관없이 앱 시작 시 실행을 끄므로, 서비스를 직접 호출해 시작할 때와 같은 일을 한다.
class AdminBootstrapIntegrationTest extends AbstractIntegrationTest {

    private static final String LOGIN_ID = "admin01";
    private static final String PASSWORD = "Admin123!";
    private static final String EMAIL = "admin@example.com";

    @Autowired
    ApplicationContext applicationContext;

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    AdminAccountService adminAccountService;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // 로컬 .env에 ADMIN_BOOTSTRAP_*를 넣어도 테스트에서는 꺼져 있어야 한다. 이 테스트는 .env에 값이 있는 환경에서 의미가 있다
    @Test
    void 테스트에서는_env의_관리자_설정과_상관없이_최초_관리자_생성이_동작하지_않는다() {
        // when & then
        assertThat(applicationContext.getBeanProvider(AdminBootstrapRunner.class).getIfAvailable()).isNull();
    }

    @Test
    void 만든_관리자로_로그인하면_ADMIN_권한_토큰을_받고_비밀번호는_인코딩되어_저장된다() throws Exception {
        // given
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command(PASSWORD));

        // when
        String body = performLogin(PASSWORD)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // then
        String accessToken = JsonPath.read(body, "$.accessToken");
        Member sut = memberRepository.findByLoginId(LOGIN_ID).orElseThrow();
        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(AdminAccountCreation.CREATED);
            softly.assertThat(jwtDecoder.decode(accessToken).getClaimAsStringList(TokenClaims.ROLES))
                    .containsExactly("ADMIN");
            softly.assertThat(sut.getType()).isEqualTo(MemberType.ADMIN);
            softly.assertThat(sut.getPassword()).isNotEqualTo(PASSWORD);
        });
    }

    @Test
    void 다시_실행해도_이미_있는_관리자의_비밀번호를_바꾸지_않는다() throws Exception {
        // given: 처음 시작할 때 만든 관리자
        adminAccountService.createAdminIfAbsent(command(PASSWORD));

        // when: 설정값의 비밀번호를 바꾸고 다시 시작했다
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command("Changed123!"));

        // then: 처음 비밀번호로만 로그인된다
        assertThat(result).isEqualTo(AdminAccountCreation.ALREADY_EXISTS);
        performLogin(PASSWORD).andExpect(status().isOk());
        performLogin("Changed123!").andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_아이디를_일반_회원이_쓰고_있으면_관리자로_바꾸지_않는다() {
        // given
        memberRepository.saveAndFlush(Member.createUser(LOGIN_ID, passwordEncoder.encode("User1234!"),
                "user@example.com", "김영수", "admin01", LocalDateTime.of(2026, 10, 1, 0, 0)));

        // when
        AdminAccountCreation result = adminAccountService.createAdminIfAbsent(command(PASSWORD));

        // then
        assertSoftly(softly -> {
            softly.assertThat(result).isEqualTo(AdminAccountCreation.LOGIN_ID_TAKEN);
            softly.assertThat(memberRepository.findByLoginId(LOGIN_ID).orElseThrow().getType())
                    .isEqualTo(MemberType.USER);
        });
    }

    private AdminAccountCommand command(String password) {
        return new AdminAccountCommand(LOGIN_ID, password, EMAIL);
    }

    private ResultActions performLogin(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(Map.of("loginId", LOGIN_ID, "password", password))));
    }

    // 로그인이 저장한 Refresh Token이 member를 외래키로 참조하므로 먼저 지운다
    private void cleanUp() {
        refreshTokenRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }

}
