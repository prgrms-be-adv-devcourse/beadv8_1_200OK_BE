package com.ok.member.auth.service.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LoginResultTest {

    @Test
    void toString에_토큰이_노출되지_않는다() {
        // given
        LoginResult result = new LoginResult("access-token", Instant.parse("2026-10-12T05:30:00Z"),
                "refresh-token", Instant.parse("2026-10-26T05:00:00Z"));

        // when
        String sut = result.toString();

        // then
        assertThat(sut)
                .doesNotContain("access-token")
                .doesNotContain("refresh-token")
                .contains("accessToken=****", "refreshToken=****");
    }

}
