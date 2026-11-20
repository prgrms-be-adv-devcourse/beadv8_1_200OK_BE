package com.ok.product.product.adapter.in.web.dto;

import java.time.LocalDateTime;

import com.ok.product.product.application.dto.UntaggedProductResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판정을 마쳤지만 태그가 하나도 붙지 않은 상품 한 항목")
public record UntaggedProductResponse(

	@Schema(description = "상품 ID", example = "128")
	Long productId,

	@Schema(description = "상품명", example = "국산 생강 1kg")
	String productName,

	@Schema(description = "마지막 판정 시각 (한국 시간)", example = "2026-11-18T14:05:31")
	LocalDateTime judgedAt,

	@Schema(description = "확률이 가장 높았던 태그 이름. 판정 뒤 태그 목록에서 빠진 태그면 null", example = "김장·김치용", nullable = true)
	String topTagLabel,

	@Schema(description = "그 태그의 \"해당\" 확률(0~1). 기준값 0.9에 얼마나 못 미쳤는지 본다", example = "0.83", nullable = true)
	Double topScore
) {

	public static UntaggedProductResponse from(UntaggedProductResult result) {
		return new UntaggedProductResponse(result.productId(), result.productName(), result.judgedAt(),
			result.topTagLabel(), result.topScore());
	}
}
