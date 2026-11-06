package com.ok.product.review.repository;

// 키워드별로 그 키워드를 표현한 리뷰 수 집계 행
public record ReviewKeywordCount(String keywordId, Long count) {
}
