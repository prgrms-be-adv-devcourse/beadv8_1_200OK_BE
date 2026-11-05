package com.ok.product.review.service.dto;

/**
 * 감성 분석기에 넘기는 리뷰 하나다.
 * 리뷰 ID는 분석기에 보내지 않고, 선택지 순서를 섞는 시드로만 쓴다. 같은 리뷰는 늘 같은 순서를 받게 하기 위해서다.
 */
public record ReviewSentimentInput(
	Long reviewId,
	String productName,
	String optionName,
	String content
) {
}
