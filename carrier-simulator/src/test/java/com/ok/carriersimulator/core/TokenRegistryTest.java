package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TokenRegistryTest {

    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void 수명이_지난_토큰은_무효다() {
        // given
        TokenRegistry sut = new TokenRegistry(Duration.ofHours(1));

        // when
        String token = sut.issue(START);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isValid(token, START.plusSeconds(3599))).isTrue();
            softly.assertThat(sut.isValid(token, START.plusSeconds(3600))).isFalse();
        });
    }

    @Test
    void 모두_만료시키면_발급한_토큰이_무효다() {
        // given
        TokenRegistry sut = new TokenRegistry(Duration.ofHours(1));
        String first = sut.issue(START);
        String second = sut.issue(START);

        // when
        sut.expireAll();

        // then
        assertSoftly(softly -> {
            softly.assertThat(first).isNotEqualTo(second);
            softly.assertThat(sut.isValid(first, START)).isFalse();
            softly.assertThat(sut.isValid(second, START)).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("모르는_토큰")
    void 모르는_토큰과_빈_값은_무효다(String token) {
        // given
        TokenRegistry sut = new TokenRegistry(Duration.ofHours(1));
        sut.issue(START);

        // when & then
        assertThat(sut.isValid(token, START)).isFalse();
    }

    static Stream<String> 모르는_토큰() {
        return Stream.of(null, "", "모르는-토큰");
    }
}
