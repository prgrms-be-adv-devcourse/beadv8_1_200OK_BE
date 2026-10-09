package com.ok.member.member;

import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.testsupport.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.problem.base-uri=https://api.example.com/problems"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public class MemberSignupIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        memberRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("회원가입에 성공하면 201과 memberId를 반환하고, 비밀번호는 해시로 저장된다")
    void signup_success() throws Exception {
        // given
        String body = signupBody("tester01", "tester@example.com", "길동이");

        // when
        ResultActions result = performSignup(body);

        // then
        result.andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").isNumber());

        Member member = memberRepository.findByLoginId("tester01").orElseThrow();
        assertThat(member.getPassword()).isNotEqualTo("password1!");
        assertThat(passwordEncoder.matches("password1!", member.getPassword())).isTrue();
    }

    @Test
    @DisplayName("중복 이메일로 가입하면 409와 ProblemDetail을 반환한다")
    void signup_duplicateEmail() throws Exception {
        // given
        performSignup(signupBody("tester01", "tester@example.com", "길동이"))
                .andExpect(status().isCreated());
        String body = signupBody("tester02", "tester@example.com", "다른닉네임");

        // when
        ResultActions result = performSignup(body);

        // then
        result.andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type", endsWith("/member/duplicate-email")));
    }

    @Test
    @DisplayName("요청 값이 유효하지 않으면 422와 필드별 에러를 반환한다")
    void signup_invalidRequest() throws Exception {
        // given
        String body = """
                {
                  "loginId": "AB",
                  "password": "short",
                  "email": "not-an-email",
                  "name": "",
                  "nickname": "닉",
                  "termsAgreed": false,
                  "privacyAgreed": true
                }
                """;

        // when
        ResultActions result = performSignup(body);

        // then
        result.andExpect(status().is(422))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[*].path", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].path", hasItem("termsAgreed")));
    }

    @Test
    @DisplayName("DB unique 제약이 중복 이메일 저장을 막는다")
    void uniqueConstraint() {
        // given
        memberRepository.saveAndFlush(buyer("tester01", "same@example.com", "닉네임1"));
        Member duplicated = buyer("tester02", "same@example.com", "닉네임2");

        // when & then
        assertThatThrownBy(() -> memberRepository.saveAndFlush(duplicated))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResultActions performSignup(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/members/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String signupBody(String loginId, String email, String nickname) {
        return """
                {
                  "loginId": "%s",
                  "password": "password1!",
                  "email": "%s",
                  "name": "홍길동",
                  "nickname": "%s",
                  "termsAgreed": true,
                  "privacyAgreed": true
                }
                """.formatted(loginId, email, nickname);
    }

    private Member buyer(String loginId, String email, String nickname) {
        return Member.createBuyer(loginId, "{bcrypt}encoded",
                email, "홍길동", nickname, LocalDateTime.now());
    }

}
