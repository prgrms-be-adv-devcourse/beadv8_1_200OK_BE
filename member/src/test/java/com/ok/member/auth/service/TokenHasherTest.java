package com.ok.member.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenHasherTest {

    @Test
    void SHA256_표준_테스트_벡터와_같은_값을_반환한다() {
        // given
        // "abc"의 SHA-256 값 (FIPS 180-2 표준 예제)
        String expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        // when
        String sut = TokenHasher.sha256Hex("abc");

        // then
        assertThat(sut).isEqualTo(expected);
    }

    @Test
    void 같은_입력은_항상_같은_해시가_된다() {
        // given
        String expected = TokenHasher.sha256Hex("token");

        // when
        String sut = TokenHasher.sha256Hex("token");

        // then
        assertThat(sut).isEqualTo(expected);
    }

    @Test
    void 해시는_16진수_소문자_64자다() {
        // when
        String sut = TokenHasher.sha256Hex("token");

        // then
        assertThat(sut).matches("^[0-9a-f]{64}$");
    }

    @Test
    void 입력이_다르면_해시도_다르다() {
        // given
        String otherHash = TokenHasher.sha256Hex("token-2");

        // when
        String sut = TokenHasher.sha256Hex("token-1");

        // then
        assertThat(sut).isNotEqualTo(otherHash);
    }

    @Test
    void 입력이_null이면_예외가_발생한다() {
        // given
        String token = null;

        // when & then
        assertThatThrownBy(() -> TokenHasher.sha256Hex(token))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("token은 null일 수 없습니다.");
    }

}
