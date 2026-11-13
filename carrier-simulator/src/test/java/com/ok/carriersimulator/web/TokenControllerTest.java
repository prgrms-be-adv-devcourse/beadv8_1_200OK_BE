package com.ok.carriersimulator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.ok.carriersimulator.testsupport.SimulatorClient;
import com.ok.carriersimulator.testsupport.SimulatorClient.HttpResult;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@SimulatorWebTest
class TokenControllerTest {

    @Autowired
    private SimulatorClient client;

    private static MultiValueMap<String, String> form(String grantType, String clientId, String clientSecret) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        if (grantType != null) {
            form.add("grant_type", grantType);
        }
        if (clientId != null) {
            form.add("client_id", clientId);
        }
        if (clientSecret != null) {
            form.add("client_secret", clientSecret);
        }
        return form;
    }

    @Test
    void 토큰을_발급한다() {
        // when
        HttpResult sut = client.requestToken(form("client_credentials", "아무-아이디", "아무-시크릿"));

        // then
        assertThat(sut.status()).isEqualTo(200);
        assertSoftly(softly -> {
            softly.assertThat(sut.<String>read("$.access_token")).isNotBlank();
            softly.assertThat(sut.<String>read("$.token_type")).isEqualTo("Bearer");
            softly.assertThat(sut.<Integer>read("$.expires_in")).isEqualTo(3600);
        });
    }

    @ParameterizedTest
    @MethodSource("받아들이지_않는_요청")
    void 인증_정보가_비거나_방식이_다르면_400이다(String grantType, String clientId, String clientSecret, String error) {
        // when
        HttpResult sut = client.requestToken(form(grantType, clientId, clientSecret));

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(400);
            softly.assertThat(sut.<String>read("$.error")).isEqualTo(error);
        });
    }

    static Stream<Arguments> 받아들이지_않는_요청() {
        return Stream.of(
                Arguments.of("client_credentials", null, "secret", "invalid_client"),
                Arguments.of("client_credentials", "id", " ", "invalid_client"),
                Arguments.of("client_credentials", "", "", "invalid_client"),
                Arguments.of("password", "id", "secret", "unsupported_grant_type"),
                Arguments.of(null, "id", "secret", "unsupported_grant_type"));
    }
}
