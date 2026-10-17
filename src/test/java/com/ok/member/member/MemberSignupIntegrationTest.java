package com.ok.member.member;

import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberSignupIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "password1!";

    @Autowired
    WebApplicationContext context;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        memberRepository.deleteAllInBatch();
    }

    @Test
    void 회원가입에_성공하면_201과_회원_id를_반환하고_비밀번호는_해시로_저장된다() throws Exception {
        // when
        ResultActions result = performSignup(signupBody("tester01", "tester@example.com"));

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").isNumber());

        Member sut = memberRepository.findByLoginId("tester01").orElse(null);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPassword()).isNotEqualTo(PASSWORD);
            softly.assertThat(passwordEncoder.matches(PASSWORD, sut.getPassword())).isTrue();
            softly.assertThat(sut.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void 대문자_아이디는_소문자로_저장되고_대소문자만_다른_아이디는_중복으로_처리한다() throws Exception {
        // given
        performSignup(signupBody("Tester01", "tester@example.com")).andExpect(status().isCreated());

        // when
        ResultActions result = performSignup(signupBody("tester01", "other@example.com"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/duplicate-login-id")));
        assertThat(memberRepository.findByLoginId("tester01")).isPresent();
    }

    @Test
    void 중복_이메일로_가입하면_409와_중복_이메일_type을_반환한다() throws Exception {
        // given
        performSignup(signupBody("tester01", "tester@example.com")).andExpect(status().isCreated());

        // when
        ResultActions result = performSignup(signupBody("tester02", "tester@example.com"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/duplicate-email")));
    }

    @Test
    void 요청_값이_유효하지_않으면_422와_필드별_오류를_반환한다() throws Exception {
        // given
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginId", "AB");
        body.put("password", "short");
        body.put("email", "not-an-email");
        body.put("name", "");
        body.put("nickname", "닉");
        body.put("termsAgreed", false);
        body.put("privacyAgreed", true);

        // when
        ResultActions result = performSignup(body);

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.errors[*].path", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].path", hasItem("termsAgreed")));
    }

    @Test
    void DB_unique_제약이_중복_이메일_저장을_막는다() {
        // given
        memberRepository.saveAndFlush(buyer("tester01", "same@example.com"));
        Member duplicated = buyer("tester02", "same@example.com");

        // when & then
        assertThatThrownBy(() -> memberRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_member_email");
    }

    @Test
    void 대문자_이메일은_소문자로_저장되고_대소문자만_다른_이메일은_중복으로_처리한다() throws Exception {
        // given
        performSignup(signupBody("tester01", "Tester@Example.COM")).andExpect(status().isCreated());

        // when
        ResultActions result = performSignup(signupBody("tester02", "tester@example.com"));

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.type", endsWith("/member/duplicate-email")));
    }

    private ResultActions performSignup(Map<String, Object> body) throws Exception {
        // 문자열 포맷으로 만들면 따옴표·백슬래시가 들어간 값에서 JSON이 깨지므로 직렬화한다
        return mockMvc.perform(post("/api/v1/members/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private Map<String, Object> signupBody(String loginId, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("loginId", loginId);
        body.put("password", PASSWORD);
        body.put("email", email);
        body.put("name", "홍길동");
        body.put("nickname", "길동이");
        body.put("termsAgreed", true);
        body.put("privacyAgreed", true);
        return body;
    }

    private Member buyer(String loginId, String email) {
        return Member.createUser(loginId, "{bcrypt}encoded", email, "홍길동", "길동이",
                LocalDateTime.of(2026, 10, 1, 12, 0));
    }


}
