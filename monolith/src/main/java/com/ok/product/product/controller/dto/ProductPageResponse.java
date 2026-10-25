package com.ok.product.product.controller.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "페이지 응답")
public record ProductPageResponse<T>(

	@Schema(description = "현재 페이지의 항목")
	List<T> content,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "전체 항목 수", example = "153")
	long totalElements,

	@Schema(description = "전체 페이지 수", example = "8")
	int totalPages
) {

	// Spring Data의 Page를 그대로 직렬화하면 내부 구조(pageable, sort 등)가 응답 계약이 되므로 필요한 값만 옮긴다
	public static <T> ProductPageResponse<T> from(Page<T> page) {
		return new ProductPageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
			page.getTotalPages());
	}
}
