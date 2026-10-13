package com.ok.product.product.controller.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.service.dto.ProductUpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "상품 수정 요청")
public record ProductUpdateRequest(

	@Schema(description = "수정 화면을 열 때 받은 상품 version. 그 사이 다른 곳에서 수정했으면 409", example = "7")
	@NotNull(message = "version은 필수입니다.")
	@PositiveOrZero(message = "version은 0 이상이어야 합니다.")
	Integer version,

	@Schema(description = "상품명", example = "청송 꿀사과")
	@NotBlank(message = "상품명은 필수입니다.")
	@Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
	String name,

	@Schema(description = "상세설명 HTML. 허용하지 않은 태그·속성과 업로드하지 않은 이미지는 저장 전에 제거됩니다.",
		example = "<p>청송 사과입니다</p>")
	@NotBlank(message = "상세설명은 필수입니다.")
	String descriptionHtml,

	@Schema(description = "하위 카테고리", example = "FRUIT")
	@NotNull(message = "카테고리는 필수입니다.")
	ProductCategory category,

	@Schema(description = "산지 (시·도)", example = "GYEONGBUK")
	@NotNull(message = "산지는 필수입니다.")
	ProductRegion region,

	@Schema(description = "판매 상태. 생략하면 그대로 둡니다.", example = "ON_SALE", nullable = true)
	ProductStatus status,

	@Schema(description = "노출 여부. 생략하면 그대로 둡니다.", example = "VISIBLE", nullable = true)
	ProductVisibility visibility,

	@Schema(description = "상품 이미지 URL (1~10장, 첫 장이 대표 이미지). 보낸 목록으로 전체 교체됩니다.")
	@NotNull(message = "상품 이미지는 필수입니다.")
	@Size(min = 1, max = 10, message = "상품 이미지는 1~10장이어야 합니다.")
	List<@NotBlank(message = "이미지 URL은 비어 있을 수 없습니다.")
	@Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다.") String> imageUrls,

	@Schema(description = "옵션 (1~50개, 순서대로 화면에 표시). id가 있으면 수정, 없으면 추가, 요청에 없는 기존 옵션은 삭제")
	@NotNull(message = "옵션은 필수입니다.")
	@Size(min = 1, max = 50, message = "옵션은 1~50개여야 합니다.")
	List<@Valid @NotNull ProductOptionUpdateRequest> options
) {

	public ProductUpdateCommand toCommand() {
		return new ProductUpdateCommand(
				version, name, descriptionHtml, category, region, status, visibility, imageUrls,
				options.stream().map(ProductOptionUpdateRequest::toSpec).toList()
		);
	}
}
