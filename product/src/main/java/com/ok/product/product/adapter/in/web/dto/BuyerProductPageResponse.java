package com.ok.product.product.adapter.in.web.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

// 판매자 목록과 함께 쓰는 ProductPageResponse에 suggestion을 넣으면 판매자 응답에도 늘 null인 필드가 생기므로 따로 둔다
@Schema(description = "구매자 상품 목록 페이지 응답")
public record BuyerProductPageResponse(

	@Schema(description = "현재 페이지의 상품")
	List<ProductSummaryResponse> content,

	@Schema(description = "현재 페이지 번호 (0부터)", example = "0")
	int page,

	@Schema(description = "페이지 크기", example = "20")
	int size,

	@Schema(description = "전체 상품 수", example = "153")
	long totalElements,

	@Schema(description = "전체 페이지 수", example = "8")
	int totalPages,

	@Schema(description = "검색어가 품목 이름의 오타로 보일 때 제안하는 바른 품목. 화면은 '혹시 고춧가루?'처럼 보여 주고, "
		+ "누르면 이 값으로 다시 검색합니다. 검색어가 없거나 제안할 품목이 없으면 null입니다",
		example = "고춧가루", nullable = true)
	String suggestion
) {

	public static BuyerProductPageResponse of(Page<ProductSummaryResponse> page, String suggestion) {
		return new BuyerProductPageResponse(page.getContent(), page.getNumber(), page.getSize(),
			page.getTotalElements(), page.getTotalPages(), suggestion);
	}
}
