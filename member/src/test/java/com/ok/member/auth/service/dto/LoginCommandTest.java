package com.ok.member.auth.service.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginCommandTest {

    @Test
    void toString에_비밀번호가_노출되지_않는다() {
        // given
        LoginCommand command = new LoginCommand("tester01", "password1!");

        // when
        String sut = command.toString();

        // then
        assertThat(sut).doesNotContain("password1!").contains("password=****");
    }

}
