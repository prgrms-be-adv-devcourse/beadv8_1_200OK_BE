package com.ok.member.member.application.service;

import com.ok.common.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TokenVersionRecordPolicyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    // 2026-11-17 12:00 (Asia/Seoul)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T03:00:00Z"), ZONE);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final TokenVersionRecordPolicy policy = new TokenVersionRecordPolicy(properties(Duration.ofMinutes(30)), CLOCK);

    @Test
    void 방금_바꿨으면_Access_Token_수명에_1분을_더한_시간_동안_남긴다() {
        // when
        Optional<Duration> sut = policy.remainingTtl(NOW);

        // then
        assertThat(sut).contains(Duration.ofMinutes(31));
    }

    @Test
    void 바꾼_지_시간이_지났으면_남은_시간만큼만_남긴다() {
        // when
        Optional<Duration> sut = policy.remainingTtl(NOW.minusMinutes(20));

        // then
        assertThat(sut).contains(Duration.ofMinutes(11));
    }

    @Test
    void 그_전에_발급된_Access_Token이_모두_만료됐으면_남기지_않는다() {
        // when
        Optional<Duration> sut = policy.remainingTtl(NOW.minusMinutes(31));

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 다시_채우기는_기록이_필요한_기간의_시작부터_본다() {
        // when
        LocalDateTime sut = policy.resyncFrom();

        // then
        assertThat(sut).isEqualTo(NOW.minusMinutes(31));
    }

    private static JwtProperties properties(Duration accessTokenExpiration) {
        return new JwtProperties("paldo-gotgan", "paldo-gotgan-api", null,
                List.of(new JwtProperties.VerificationKey("rs256-v1", new ClassPathResource("jwt/test-public.pem"))),
                null, accessTokenExpiration, Duration.ofDays(14));
    }

}
