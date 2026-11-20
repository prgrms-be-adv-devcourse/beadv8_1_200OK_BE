package com.ok.product.product.application.dto;

import java.time.LocalDateTime;

/**
 * 작업자가 꺼낸 태그 판정 요청 하나와 판정기에 넘길 상품 내용이다.
 *
 * @param requestedAt 꺼낼 때 읽은 요청 세대. 저장할 때 값이 다르면 판정하는 동안 상품이 바뀐 것이다
 */
public record ProductTagTarget(
	Long judgmentId,
	Long productId,
	LocalDateTime requestedAt,
	ProductTagInput input
) {
}
