package com.ok.product.review.client.jev;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ok.product.review.client.ReviewAnalysisFailedException;
import com.ok.product.review.client.ReviewAnalyzerUnavailableException;

import lombok.RequiredArgsConstructor;

/**
 * Jev 분석 API를 부르고 실패를 재시도할 실패, 최종 실패, 분석기를 쓸 수 없는 실패로 나눈다.
 * 감성·키워드 분석기가 이 한 곳을 거쳐, 같은 오류를 두 작업자가 다르게 다루지 않게 한다.
 */
@Component
@RequiredArgsConstructor
class JevClient {

	private static final String ANALYZE_PATH = "/v1/systemone";

	private final RestClient jevRestClient;
	private final JevProperties properties;

	JevResponse send(JevRequest request) {
		try {
			return jevRestClient.post()
				.uri(ANALYZE_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(JevResponse.class);
		} catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
			// 키가 없거나 폐기·교체된 설정 오류다. 키를 고치면 같은 요청이 성공하므로 요청의 최종 실패로 보지 않는다
			throw new ReviewAnalyzerUnavailableException("Jev가 인증을 거절했습니다. status=" + e.getStatusCode().value(), e);
		} catch (HttpClientErrorException e) {
			String message = "Jev가 요청을 거절했습니다. status=" + e.getStatusCode().value();
			if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw ReviewAnalysisFailedException.retryable(message, e);
			}
			throw ReviewAnalysisFailedException.finalFailure(message, e);
		} catch (HttpServerErrorException e) {
			throw ReviewAnalysisFailedException.retryable("Jev 서버 오류입니다. status=" + e.getStatusCode().value(), e);
		} catch (ResourceAccessException e) {
			// 연결 실패와 시간 초과
			throw ReviewAnalysisFailedException.retryable("Jev에 연결하지 못했거나 응답이 없습니다.", e);
		} catch (RestClientException e) {
			throw ReviewAnalysisFailedException.finalFailure("Jev 응답을 읽지 못했습니다.", e);
		}
	}
}
