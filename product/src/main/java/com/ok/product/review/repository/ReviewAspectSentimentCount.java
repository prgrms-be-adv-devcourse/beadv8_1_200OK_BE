package com.ok.product.review.repository;

import com.ok.product.review.domain.ReviewAspect;
import com.ok.product.review.domain.Sentiment;

// 분석 항목·감성별 리뷰 수 집계 행
public record ReviewAspectSentimentCount(ReviewAspect aspect, Sentiment sentiment, Long count) {
}
