package com.ok.product.product.controller.dto;

import com.ok.product.product.service.dto.ProductCategoryCode;
import com.ok.product.product.service.dto.ProductCategoryGroupCode;
import com.ok.product.product.service.dto.ProductRegionCode;
import com.ok.product.product.service.dto.ProductStatusCode;
import com.ok.product.product.service.dto.ProductVisibilityCode;
import com.ok.product.product.service.dto.SellerProductDetailResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "상품 상세 (판매자 수정 화면용)")
public record SellerProductDetailResponse(

	@Schema(description = "상품 ID", example = "1")
	Long productId,

	@Schema(description = "상품 version. 상품 수정 요청에 그대로 보낸다", example = "7")
	Integer version,

	@Schema(description = "상품명", example = "청송 꿀사과")
	String name,

	@Schema(description = "상세설명 HTML (저장할 때 정제됨)")
	String descriptionHtml,

	@Schema(description = "상위 카테고리", example = "AGRICULTURE")
	ProductCategoryGroupCode categoryGroup,

	@Schema(description = "하위 카테고리", example = "FRUIT")
	ProductCategoryCode category,

	@Schema(description = "산지 (시·도)", example = "GYEONGBUK")
	ProductRegionCode region,

	@Schema(description = "판매 상태", example = "ON_SALE")
	ProductStatusCode status,

	@Schema(description = "노출 여부", example = "VISIBLE")
	ProductVisibilityCode visibility,

	@Schema(description = "이미지 URL. 첫 장이 대표 이미지")
	List<String> imageUrls,

	@Schema(description = "옵션 (화면 순서, 삭제한 옵션 제외)")
	List<SellerProductOptionResponse> options,

	@Schema(description = "등록 시각")
	LocalDateTime createdAt,

	@Schema(description = "마지막 수정 시각")
	LocalDateTime updatedAt
) {

	public static SellerProductDetailResponse from(SellerProductDetailResult result) {
		return new SellerProductDetailResponse(result.productId(), result.version(), result.name(),
			result.descriptionHtml(), result.categoryGroup(), result.category(), result.region(), result.status(),
			result.visibility(), result.imageUrls(),
			result.options().stream().map(SellerProductOptionResponse::from).toList(), result.createdAt(),
			result.updatedAt());
	}
}
