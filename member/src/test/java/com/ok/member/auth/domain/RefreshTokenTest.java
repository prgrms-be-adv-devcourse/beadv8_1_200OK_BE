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

    @Test
    void 회전하면_새_해시와_회전_일시와_만료_일시로_바뀐다() {
        // given
        RefreshToken sut = RefreshToken.create(1L, "old-hash", EXPIRES_AT);
        LocalDateTime rotatedAt = EXPIRES_AT.minusDays(1);
        LocalDateTime newExpiresAt = rotatedAt.plusDays(14);

        // when
        sut.rotate("new-hash", rotatedAt, newExpiresAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(1L);
            softly.assertThat(sut.getTokenHash()).isEqualTo("new-hash");
            softly.assertThat(sut.getRotatedAt()).isEqualTo(rotatedAt);
            softly.assertThat(sut.getExpiresAt()).isEqualTo(newExpiresAt);
        });
    }

    @Test
    void 생성만_하면_회전_일시가_없다() {
        // when
        RefreshToken sut = RefreshToken.create(1L, "hash", EXPIRES_AT);

        // then
        assertThat(sut.getRotatedAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("회전_필수값이_null인_입력")
    void 회전_필수값이_null이면_회전하지_않는다(String newTokenHash, LocalDateTime rotatedAt, LocalDateTime newExpiresAt,
                                    String message) {
        // given
        RefreshToken sut = RefreshToken.create(1L, "hash", EXPIRES_AT);

        // when & then
        assertThatThrownBy(() -> sut.rotate(newTokenHash, rotatedAt, newExpiresAt))
                .isInstanceOf(NullPointerException.class)
                .hasMessage(message);
        assertThat(sut.getTokenHash()).isEqualTo("hash");
    }

    static Stream<Arguments> 회전_필수값이_null인_입력() {
        return Stream.of(
                Arguments.of(null, EXPIRES_AT, EXPIRES_AT, "newTokenHash는 null일 수 없습니다."),
                Arguments.of("new-hash", null, EXPIRES_AT, "rotatedAt은 null일 수 없습니다."),
                Arguments.of("new-hash", EXPIRES_AT, null, "newExpiresAt은 null일 수 없습니다.")
        );
    }

}
