package com.ok.product.product.application.dto;

import java.time.LocalDateTime;

/**
 * 판정을 마쳤지만 태그가 하나도 붙지 않은 상품 한 항목.
 *
 * @param topTagLabel 확률이 가장 높았던 태그 이름. 판정 뒤 목록에서 빠진 태그면 없다
 * @param topScore    그 태그의 "해당" 확률. 기준값(0.9)에 얼마나 못 미쳤는지 본다
 */
public record UntaggedProductResult(
	Long productId,
	String productName,
	LocalDateTime judgedAt,
	String topTagLabel,
	Double topScore
) {
}
