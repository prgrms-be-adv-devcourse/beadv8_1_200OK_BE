package com.ok.product.review.service.dto;

import java.util.List;

// 첨부는 부분 수정 없이 요청 목록으로 통째로 바꾼다
public record ProductReviewUpdateCommand(
	int rating,
	String content,
	List<String> mediaUrls
) {
}
