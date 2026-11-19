package com.ok.product.product.application.dto;

import java.time.LocalDateTime;

/**
 * 저장소가 읽은 태그 0개 상품 한 행. 태그 이름은 태그 목록 파일에 있어 서비스가 {@link UntaggedProductResult}로 바꾼다.
 */
public record UntaggedProductRow(
	Long productId,
	String productName,
	LocalDateTime judgedAt,
	String topTagId,
	Double topScore
) {
}
