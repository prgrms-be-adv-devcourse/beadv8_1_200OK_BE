package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.dto.LoginCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class LoginRequestTest {

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
    @ResourceLock(Resources.LOCALE)  // 기본 로케일을 전역으로 바꾸므로, 병렬 실행 시 로케일을 쓰는 다른 테스트와 동시에 돌지 않게 한다
    void 서버_기본_로케일과_관계없이_아이디를_같은_값으로_정규화한다() {
        // given
        // 터키어 로케일에서 기본 toLowerCase()는 "I"를 점 없는 "ı"로 바꾼다
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));

        try {
            // when
            LoginRequest sut = new LoginRequest("ADMIN01", "password1!");

            // then
            assertThat(sut.loginId()).isEqualTo("admin01");
        } finally {
            Locale.setDefault(original);
        }
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
