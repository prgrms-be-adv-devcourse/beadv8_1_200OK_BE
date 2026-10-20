package com.ok.product.review.client;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.ok.common.exception.RestApiException;
import com.ok.product.review.client.dto.OrderReviewCandidateResponse;
import com.ok.product.review.exception.ProductReviewErrorCode;
import com.ok.product.review.service.dto.ReviewablePurchase;

/**
 * 주문 모듈 내부 API를 HTTP로 불러 리뷰를 쓸 수 있는 구매 건을 가져온다.
 * 주문 모듈의 {@code ProductApiClient}와 같은 방식(타임아웃, 실패를 모듈 에러 코드로 변환)을 따른다.
 */
@Component
public class OrderApiClient {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2); // 연결까지 최대 2초
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);    // 응답까지 최대 3초

	private final RestClient restClient;

	public OrderApiClient(@Value("${app.internal-api.base-url:http://localhost:8080}") String baseUrl) {
		// 타임아웃이 없으면 주문 모듈이 응답하지 않을 때 리뷰 작성 요청도 끝없이 기다린다
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT);
		factory.setReadTimeout(READ_TIMEOUT);

		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/internal/v1/orders")
			.requestFactory(factory)
			// 서버가 꺼졌거나 타임아웃이면 IOException이 나고, 그대로 두면 500이 된다
			.requestInterceptor((request, body, execution) -> {
				try {
					return execution.execute(request, body);
				} catch (IOException e) {
					throw new RestApiException(ProductReviewErrorCode.ORDER_API_FAILED);
				}
			})
			// 응답은 받았지만 오류 상태(422, 500 등)인 경우
			.defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
				throw new RestApiException(ProductReviewErrorCode.ORDER_API_FAILED);
			})
			.build();
	}

	/**
	 * 회원이 이 상품을 사서 구매확정한 주문 상품 목록을 가져온다. 없으면 빈 목록이다.
	 *
	 * @throws RestApiException {@code ORDER_API_FAILED} 주문 모듈이 응답하지 않거나 오류를 돌려주면
	 */
	public List<ReviewablePurchase> findReviewablePurchases(Long memberId, Long productId) {
		OrderReviewCandidateResponse[] response = restClient.get()
			.uri("/review-candidates?memberId={memberId}&productId={productId}", memberId, productId)
			.retrieve()
			.body(OrderReviewCandidateResponse[].class);

		if (response == null) {
			return List.of();
		}
		return Arrays.stream(response)
			.map(candidate -> new ReviewablePurchase(candidate.orderId(), candidate.orderItemId(), candidate.productOptionId()))
			.toList();
	}
}
