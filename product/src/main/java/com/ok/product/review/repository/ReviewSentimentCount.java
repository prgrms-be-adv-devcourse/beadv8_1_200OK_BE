package com.ok.product.review.repository;

import com.ok.product.review.domain.Sentiment;

// 감성별 리뷰 수 집계 행
public record ReviewSentimentCount(Sentiment sentiment, Long count) {
}
