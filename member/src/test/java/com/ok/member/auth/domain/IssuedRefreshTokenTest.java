package com.ok.member.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IssuedRefreshTokenTest {

    @Test
    void toString에_토큰_원문이_노출되지_않는다() {
        // given
        IssuedRefreshToken issued = new IssuedRefreshToken("raw-token-value",
                Instant.parse("2026-10-26T03:00:00Z"));

        // when
        String sut = issued.toString();

        // then
        assertThat(sut).doesNotContain("raw-token-value").contains("value=****");
    }

}
