package com.ok.member.auth.config;

import com.ok.common.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서명 키 설정이 잘못되면 토큰을 발급하기 전, 인코더를 만드는 기동 시점에 실패하는지 확인한다.
 */
class JwtEncoderConfigTest {

    private static final String KEY_ID = "rs256-v1";
    private static final Resource PUBLIC_KEY = new ClassPathResource("jwt/test-public.pem");
    private static final Resource PRIVATE_KEY = new ClassPathResource("jwt/test-private.pem");
    private static final JwtProperties PROPERTIES = new JwtProperties("paldo-gotgan", "paldo-gotgan-api",
            List.of(new JwtProperties.VerificationKey(KEY_ID, PUBLIC_KEY)), Duration.ofMinutes(30), Duration.ofDays(14));

    private final JwtEncoderConfig jwtEncoderConfig = new JwtEncoderConfig();

    @Test
    void 서명_키와_검증_키가_짝이면_인코더가_만들어진다() {
        // given
        JwtSigningProperties signing = new JwtSigningProperties(KEY_ID, PRIVATE_KEY);

        // when
        JwtEncoder sut = jwtEncoderConfig.jwtEncoder(PROPERTIES, signing);

        // then
        assertThat(sut).isNotNull();
    }

    @Test
    void 서명_kid의_공개키가_검증_목록에_없으면_예외가_발생한다() {
        // given
        JwtSigningProperties signing = new JwtSigningProperties("rs256-v2", PRIVATE_KEY);

        // when & then
        assertThatThrownBy(() -> jwtEncoderConfig.jwtEncoder(PROPERTIES, signing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jwt.signing.key-id(rs256-v2)의 공개키가 jwt.verification-keys에 없습니다.");
    }

    @Test
    void 개인키가_kid의_공개키와_짝이_아니면_예외가_발생한다() throws NoSuchAlgorithmException {
        // given
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        byte[] otherPrivateKey = generator.generateKeyPair().getPrivate().getEncoded();
        String pem = "-----BEGIN PRIVATE KEY-----\n%s\n-----END PRIVATE KEY-----\n"
                .formatted(Base64.getMimeEncoder().encodeToString(otherPrivateKey));
        JwtSigningProperties signing = new JwtSigningProperties(
                KEY_ID, new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8)));

        // when & then
        assertThatThrownBy(() -> jwtEncoderConfig.jwtEncoder(PROPERTIES, signing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("jwt.signing.private-key가 kid(rs256-v1)의 공개키와 짝이 아닙니다.");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_문자열")
    void 서명_kid가_비어_있으면_예외가_발생한다(String keyId) {
        // when & then
        assertThatThrownBy(() -> new JwtSigningProperties(keyId, PRIVATE_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.signing.key-id는 필수입니다.");
    }

    static Stream<String> 비어_있는_문자열() {
        return Stream.of(null, "", " ");
    }

    @Test
    void 개인키_위치가_없으면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new JwtSigningProperties(KEY_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jwt.signing.private-key는 필수입니다.");
    }

}
