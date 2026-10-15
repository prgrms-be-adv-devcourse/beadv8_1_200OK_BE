package com.ok.member.auth.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefreshTokenTest {

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 10, 26, 12, 0);

    @Test
    void 생성하면_회원_id와_해시와_만료_일시가_저장된다() {
        // when
        RefreshToken sut = RefreshToken.create(1L, "hash", EXPIRES_AT);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(1L);
            softly.assertThat(sut.getTokenHash()).isEqualTo("hash");
            softly.assertThat(sut.getExpiresAt()).isEqualTo(EXPIRES_AT);
        });
    }

    @Test
    void 만료_시각_이전이면_만료되지_않았다() {
        // given
        RefreshToken sut = RefreshToken.create(1L, "hash", EXPIRES_AT);

        // when
        boolean expired = sut.isExpired(EXPIRES_AT.minusNanos(1));

        // then
        assertThat(expired).isFalse();
    }

    @ParameterizedTest
    @MethodSource("만료_시각과_같거나_지난_시각")
    void 만료_시각과_같거나_지나면_만료되었다(LocalDateTime now) {
        // given
        RefreshToken sut = RefreshToken.create(1L, "hash", EXPIRES_AT);

        // when
        boolean expired = sut.isExpired(now);

        // then
        assertThat(expired).isTrue();
    }

    static Stream<LocalDateTime> 만료_시각과_같거나_지난_시각() {
        return Stream.of(EXPIRES_AT, EXPIRES_AT.plusNanos(1), EXPIRES_AT.plusDays(1));
    }

    @ParameterizedTest
    @MethodSource("필수값이_null인_입력")
    void 필수값이_null이면_생성할_수_없다(Long memberId, String tokenHash, LocalDateTime expiresAt, String message) {
        // when & then
        assertThatThrownBy(() -> RefreshToken.create(memberId, tokenHash, expiresAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 필수값이_null인_입력() {
        return Stream.of(
                Arguments.of(null, "hash", EXPIRES_AT, "memberId는 null일 수 없습니다."),
                Arguments.of(1L, null, EXPIRES_AT, "tokenHash는 null일 수 없습니다."),
                Arguments.of(1L, "hash", null, "expiresAt은 null일 수 없습니다.")
        );
    }

}
