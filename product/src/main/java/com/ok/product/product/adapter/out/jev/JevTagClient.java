package com.ok.product.product.adapter.out.jev;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ok.product.config.JevProperties;
import com.ok.product.product.exception.ProductTagJudgeFailedException;
import com.ok.product.product.exception.ProductTagJudgeUnavailableException;

import lombok.RequiredArgsConstructor;

/**
 * Jev 판정 API를 부르고 실패를 재시도할 실패, 최종 실패, 판정기를 쓸 수 없는 실패로 나눈다.
 * 분류 규칙은 리뷰 분석의 Jev 호출과 같다. 하위 도메인끼리 클래스를 쓸 수 없어 상품 쪽 예외로 따로 둔다.
 * 빈 이름이 리뷰 쪽 {@code jevClient}와 겹치면 앱이 뜨지 않아 이름을 달리한다.
 */
@Component
@RequiredArgsConstructor
class JevTagClient {

	private static final String JUDGE_PATH = "/v1/systemone";

	private final RestClient jevRestClient;
	private final JevProperties properties;

	JevResponse send(JevRequest request) {
		try {
			return jevRestClient.post()
				.uri(JUDGE_PATH)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.retrieve()
				.body(JevResponse.class);
		} catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
			// 키가 없거나 폐기·교체된 설정 오류다. 키를 고치면 같은 요청이 성공하므로 요청의 최종 실패로 보지 않는다
			throw new ProductTagJudgeUnavailableException("Jev가 인증을 거절했습니다. status=" + e.getStatusCode().value(), e);
		} catch (HttpClientErrorException e) {
			String message = "Jev가 요청을 거절했습니다. status=" + e.getStatusCode().value();
			if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw ProductTagJudgeFailedException.retryable(message, e);
			}
			throw ProductTagJudgeFailedException.finalFailure(message, e);
		} catch (HttpServerErrorException e) {
			throw ProductTagJudgeFailedException.retryable("Jev 서버 오류입니다. status=" + e.getStatusCode().value(), e);
		} catch (ResourceAccessException e) {
			// 연결 실패와 시간 초과
			throw ProductTagJudgeFailedException.retryable("Jev에 연결하지 못했거나 응답이 없습니다.", e);
		} catch (RestClientException e) {
			throw ProductTagJudgeFailedException.finalFailure("Jev 응답을 읽지 못했습니다.", e);
		}
	}
}
