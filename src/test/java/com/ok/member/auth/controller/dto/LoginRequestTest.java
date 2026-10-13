package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.dto.LoginCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

public class LoginRequestTest {

    @Test
    void 아이디는_앞뒤_공백을_제거하고_소문자로_바꾸고_비밀번호는_그대로_둔다() {
        // when
        LoginRequest sut = new LoginRequest(" Tester01 ", " Password1! ");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.loginId()).isEqualTo("tester01");
            softly.assertThat(sut.password()).isEqualTo(" Password1! ");
        });
    }

    @Test
    void 아이디가_null이면_null을_유지해_필수값_검증에서_걸리게_한다() {
        // when
        LoginRequest sut = new LoginRequest(null, "password1!");

        // then
        assertThat(sut.loginId()).isNull();
    }

    @Test
    void toCommand는_아이디와_비밀번호를_각_필드로_옮긴다() {
        // given
        LoginRequest request = new LoginRequest("tester01", "password1!");

        // when
        LoginCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.loginId()).isEqualTo("tester01");
            softly.assertThat(sut.password()).isEqualTo("password1!");
        });
    }

    @Test
    void toString에_비밀번호가_노출되지_않는다() {
        // given
        LoginRequest request = new LoginRequest("tester01", "password1!");

        // when
        String sut = request.toString();

        // then
        assertThat(sut).doesNotContain("password1!").contains("password=****");
    }

}
