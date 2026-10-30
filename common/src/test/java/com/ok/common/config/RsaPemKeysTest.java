package com.ok.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RsaPemKeysTest {

    private static final Resource PUBLIC_KEY = new ClassPathResource("jwt/test-public.pem");
    private static final Resource PRIVATE_KEY = new ClassPathResource("jwt/test-private.pem");

    @Test
    void 공개키와_개인키를_읽으면_같은_키_쌍이다() {
        // given
        RSAPublicKey publicKey = RsaPemKeys.publicKey(PUBLIC_KEY);

        // when
        RSAPrivateKey sut = RsaPemKeys.privateKey(PRIVATE_KEY);

        // then
        assertThat(sut.getModulus()).isEqualTo(publicKey.getModulus());
    }

    @Test
    void 파일이_없으면_위치를_담은_예외가_발생한다() {
        // given
        Resource missing = new ClassPathResource("jwt/missing.pem");

        // when & then
        assertThatThrownBy(() -> RsaPemKeys.publicKey(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT 키 파일을 읽을 수 없습니다: class path resource [jwt/missing.pem]. "
                        + RsaPemKeys.KEY_GUIDE);
    }

    @Test
    void 공개키_자리에_개인키를_넣으면_형식_오류가_발생한다() {
        // when & then
        assertThatThrownBy(() -> RsaPemKeys.publicKey(PRIVATE_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT 키 파일이 X.509 공개키(-----BEGIN PUBLIC KEY-----) 형식이 아닙니다: "
                        + "class path resource [jwt/test-private.pem]. " + RsaPemKeys.KEY_GUIDE);
    }

    @Test
    void 개인키_형식_오류_메시지에_키_내용이_노출되지_않는다() {
        // given
        // PKCS#1 헤더는 읽지 않는다. 본문이 메시지에 섞여 나오지 않는지 확인한다
        String body = "c2VjcmV0LWtleS1ib2R5";
        Resource pkcs1 = pem("RSA PRIVATE KEY", body);

        // when & then
        assertThatThrownBy(() -> RsaPemKeys.privateKey(pkcs1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT 키 파일이 PKCS#8 개인키(-----BEGIN PRIVATE KEY-----) 형식이 아닙니다: %s. %s"
                        .formatted(pkcs1.getDescription(), RsaPemKeys.KEY_GUIDE))
                .hasMessageNotContaining(body);
    }

    @Test
    void 키가_2048비트보다_짧으면_예외가_발생한다() throws NoSuchAlgorithmException {
        // given
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        Resource shortKey = pem("PUBLIC KEY", Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded()));

        // when & then
        assertThatThrownBy(() -> RsaPemKeys.publicKey(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT 키는 2048비트 이상이어야 합니다: %s. %s"
                        .formatted(shortKey.getDescription(), RsaPemKeys.KEY_GUIDE));
    }

    private static Resource pem(String type, String base64Body) {
        String pem = "-----BEGIN %s-----\n%s\n-----END %s-----\n".formatted(type, base64Body, type);
        return new ByteArrayResource(pem.getBytes(StandardCharsets.UTF_8));
    }

}
