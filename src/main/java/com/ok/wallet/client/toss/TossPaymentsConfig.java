package com.ok.wallet.client.toss;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(TossPaymentsProperties.class)
class TossPaymentsConfig {

    // 토스 API 전용 RestClient. 시크릿 키 Basic 인증과 타임아웃을 여기서 한 번만 설정한다.
    @Bean
    RestClient tossRestClient(TossPaymentsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, basicAuth(properties.secretKey()))
                .build();
    }

    // 토스는 "시크릿 키:" (비밀번호 없이 콜론만)를 Base64로 인코딩한 Basic 인증을 쓴다
    private static String basicAuth(String secretKey) {
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }
}
