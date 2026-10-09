package com.ok.member.member.service.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SignupCommandTest {

    @Test
    @DisplayName("toString에 비밀번호 평문이 포함되지 않는다")
    void toString_masksPassword() {
        // given
        SignupCommand command = new SignupCommand(
                "tester01", "password1!", "tester@example.com",
                "홍길동", "길동이");

        // when
        String result = command.toString();

        // then
        assertThat(result).doesNotContain("password1!");
        assertThat(result).contains("password=****");
    }

}
