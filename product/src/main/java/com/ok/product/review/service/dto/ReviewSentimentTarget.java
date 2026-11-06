package com.ok.product.review.service.dto;

import java.time.LocalDateTime;

/**
 * 작업자가 꺼낸 감성 분석 요청 하나와 분석기에 넘길 리뷰 내용이다.
 *
 * @param reviewUpdatedAt 리뷰 내용을 읽은 시점의 리뷰 수정 시각. 결과에 함께 남긴다
 */
public record ReviewSentimentTarget(
	Long requestId,
	Long reviewId,
	LocalDateTime reviewUpdatedAt,
	ReviewSentimentInput input
) {
}
