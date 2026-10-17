package com.ok.product.review.service.dto;

// 타입은 enum 이름 문자열로 준다. 컨트롤러 계층이 domain 타입을 쓰지 않게 하려는 것 (ArchitectureTest)
public record ProductReviewMediaResult(
	String url,
	String type
) {
}
