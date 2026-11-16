package com.ok.delivery.adapter.out.carrier;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
@ConditionalOnProperty(name = "delivery.carrier.type", havingValue = "tracker")
@EnableConfigurationProperties(TrackerProperties.class)
class TrackerConfig {

    // 비밀값은 알림 API가 읽는 delivery.carrier.webhook.secret이라 속성 클래스가 아니라 여기서 맞춰 본다
    @Bean
    InitializingBean trackerWebhookSecretCheck(TrackerProperties properties,
                                               @Value("${delivery.carrier.webhook.secret:}") String webhookSecret) {
        return () -> properties.requireMatchingWebhookSecret(webhookSecret);
    }

    // Delivery Tracker 전용 RestClient. 토큰은 만료되면 바뀌므로 기본 헤더가 아니라 호출마다 붙인다
    @Bean
    RestClient trackerRestClient(TrackerProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
