package com.ok.member.member.controller.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class SignupRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("아이디와 이메일은 앞뒤 공백을 제거하고 소문자로 변환한다")
    void normalize_loginIdAndEmail() {
        // given & when
        SignupRequest request = request(" Tester01 ", " Tester@Example.COM ",
                "홍길동", "길동이");

        // then
        assertThat(request.loginId()).isEqualTo("tester01");
        assertThat(request.email()).isEqualTo("tester@example.com");
    }

    @Test
    @DisplayName("이름, 닉네임, 전화번호는 앞뒤 공백만 제거하고 대소문자는 유지한다")
    void normalize_trimOnly() {
        // given & when
        SignupRequest request = request("tester01", "tester@example.com",
                "  Hong Gildong ", " GilDong ");

        // then
        assertThat(request.name()).isEqualTo("Hong Gildong");
        assertThat(request.nickname()).isEqualTo("GilDong");
    }

    @Test
    @DisplayName("비밀번호는 변환하지 않는다")
    void normalize_passwordUnchanged() {
        // given & when
        SignupRequest request = new SignupRequest(
                "tester01", " Password1! ", "tester@example.com",
                "홍길동", "길동이", true, true);

        // then
        assertThat(request.password()).isEqualTo(" Password1! ");
    }

    @Test
    @DisplayName("값이 null이면 null을 유지해 검증 단계에서 필수값 에러가 나도록 한다")
    void normalize_null() {
        // given & when
        SignupRequest request = request(null, null, null, null);

        // then
        assertThat(request.loginId()).isNull();
        assertThat(request.email()).isNull();
        assertThat(request.name()).isNull();
        assertThat(request.nickname()).isNull();
    }

    @Test
    @DisplayName("toString에 비밀번호 평문이 포함되지 않는다")
    void toString_masksPassword() {
        // given
        SignupRequest request = request("tester01", "tester@example.com",
                "홍길동", "길동이");

        // when
        String result = request.toString();

        // then
        assertThat(result).doesNotContain("password1!");
        assertThat(result).contains("password=****");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b", "tester@example", "tester@.com", "tester@example.c", "@example.com"})
    @DisplayName("최상위 도메인이 없거나 형식이 잘못된 이메일은 검증에 실패한다")
    void validate_invalidEmail(String email) {
        // given
        SignupRequest request = request("tester01", email, "홍길동", "길동이");

        // when
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tester@example.com", "tester.01+shop@mail.example.co.kr", "Tester@Example.COM"})
    @DisplayName("올바른 형식의 이메일은 검증을 통과한다")
    void validate_validEmail(String email) {
        // given
        SignupRequest request = request("tester01", email, "홍길동", "길동이");

        // when
        Set<ConstraintViolation<SignupRequest>> violations = validator.validate(request);

        // then
        assertThat(violations).isEmpty();
    }

    private SignupRequest request(String loginId, String email, String name, String nickname) {
        return new SignupRequest(loginId, "password1!", email, name,
                nickname,true, true);
    }

}
