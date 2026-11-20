package com.ok.product.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Jev API를 부르는 {@code RestClient}를 만든다. 리뷰 분석과 상품 태그 판정이 같은 빈을 쓴다.
 */
@Configuration
@EnableConfigurationProperties(JevProperties.class)
class JevConfig {

	// 시간 초과가 없으면 Jev가 응답하지 않을 때 작업자가 다음 요청을 처리하지 못하고 멈춘다
	@Bean
	RestClient jevRestClient(JevProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return RestClient.builder()
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.build();
	}
}
