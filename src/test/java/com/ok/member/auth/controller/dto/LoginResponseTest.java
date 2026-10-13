package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.dto.LoginResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

public class LoginResponseTest {

    private static final Instant ACCESS_EXPIRES_AT = Instant.parse("2026-10-12T05:30:00Z");
    private static final Instant REFRESH_EXPIRES_AT = Instant.parse("2026-10-26T05:00:00Z");

    @Test
    void 로그인_결과의_토큰과_만료_시각을_각_필드로_옮기고_토큰_유형은_Bearer다() {
        // given
        // 같은 타입 필드(토큰 2개, 만료 시각 2개)가 뒤바뀌지 않았는지 확인하려고 값을 모두 다르게 둔다
        LoginResult result = new LoginResult("access-token", ACCESS_EXPIRES_AT, "refresh-token", REFRESH_EXPIRES_AT);

        // when
        LoginResponse sut = LoginResponse.from(result);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.tokenType()).isEqualTo("Bearer");
            softly.assertThat(sut.accessToken()).isEqualTo("access-token");
            softly.assertThat(sut.accessTokenExpiresAt()).isEqualTo(ACCESS_EXPIRES_AT);
            softly.assertThat(sut.refreshToken()).isEqualTo("refresh-token");
            softly.assertThat(sut.refreshTokenExpiresAt()).isEqualTo(REFRESH_EXPIRES_AT);
        });
    }

    @Test
    void toString에_토큰이_노출되지_않는다() {
        // given
        LoginResponse response = LoginResponse.from(
                new LoginResult("access-token", ACCESS_EXPIRES_AT, "refresh-token", REFRESH_EXPIRES_AT));

        // when
        String sut = response.toString();

        // then
        assertThat(sut)
                .doesNotContain("access-token")
                .doesNotContain("refresh-token")
                .contains("accessToken=****", "refreshToken=****");
    }

}
