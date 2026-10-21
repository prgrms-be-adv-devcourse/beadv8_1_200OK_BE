package com.ok.testsupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.ok.product.review.client.OrderApiClient;
import com.ok.product.review.service.dto.ReviewablePurchase;

/**
 * 주문 모듈 내부 API 대신 메모리에 넣어 둔 구매 건을 돌려주는 Fake.
 * 통합 테스트는 웹 서버 없이(MOCK) 돌아 자기 자신을 HTTP로 부를 수 없고, 로컬에 서버가 떠 있으면 그쪽 DB를 보게 되어 대신 쓴다.
 * {@code TestAccessTokens}처럼 {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고, {@code @Primary}로 실제 클라이언트를 대신한다.
 * 테스트가 {@link #add}로 회원·상품별 구매 건을 넣고, 끝나면 {@link #clear}로 비운다. 동시 요청 테스트가 있어 동시성 컬렉션을 쓴다.
 */
@Component
@Primary
public class FakeOrderApiClient extends OrderApiClient {

	private final Map<String, List<ReviewablePurchase>> purchases = new ConcurrentHashMap<>();

	public FakeOrderApiClient() {
		super("http://localhost:0"); // 실제로 호출하지 않으므로 주소는 의미 없다
	}

	public void add(Long memberId, Long productId, ReviewablePurchase purchase) {
		purchases.computeIfAbsent(key(memberId, productId), key -> new CopyOnWriteArrayList<>()).add(purchase);
	}

	public void clear() {
		purchases.clear();
	}

	@Override
	public List<ReviewablePurchase> findReviewablePurchases(Long memberId, Long productId) {
		return purchases.getOrDefault(key(memberId, productId), List.of());
	}

	private String key(Long memberId, Long productId) {
		return memberId + ":" + productId;
	}
}
