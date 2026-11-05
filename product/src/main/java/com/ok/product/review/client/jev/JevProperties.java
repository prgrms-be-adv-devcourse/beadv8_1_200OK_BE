package com.ok.product.review.client.jev;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Jev API 설정. {@code app.review-analysis.jev.*} 값이 바인딩된다.
 * API 키는 비어 있어도 앱이 뜬다. 분석 작업자는 운영 설정에서만 켜고, 운영은 compose가 {@code JEV_API_KEY}를 요구한다.
 *
 * @param model       요청에 담는 모델. #385 실험과 같은 고정 버전을 쓴다
 * @param readTimeout 응답 대기 시간 초과. 넘으면 재시도할 실패다
 */
@ConfigurationProperties(prefix = "app.review-analysis.jev")
public record JevProperties(
	String baseUrl,
	String apiKey,
	String model,
	Duration connectTimeout,
	Duration readTimeout
) {

	public JevProperties {
		if (baseUrl == null || baseUrl.isBlank()) {
			throw new IllegalArgumentException("app.review-analysis.jev.base-url이 설정되지 않았습니다.");
		}
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("app.review-analysis.jev.model이 설정되지 않았습니다.");
		}
		if (connectTimeout == null || readTimeout == null) {
			throw new IllegalArgumentException("app.review-analysis.jev의 connect-timeout, read-timeout이 설정되지 않았습니다.");
		}
	}

	// 레코드의 기본 toString은 모든 필드를 담으므로, 로그나 예외 메시지에 API 키가 남지 않게 가린다
	@Override
	public String toString() {
		return "JevProperties[baseUrl=" + baseUrl
			+ ", apiKey=****"
			+ ", model=" + model
			+ ", connectTimeout=" + connectTimeout
			+ ", readTimeout=" + readTimeout + "]";
	}
}
