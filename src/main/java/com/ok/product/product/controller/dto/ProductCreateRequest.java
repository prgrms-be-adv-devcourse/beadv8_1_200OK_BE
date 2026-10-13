package com.ok.product.product.controller.dto;

import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.service.dto.ProductCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "상품 등록 요청")
public record ProductCreateRequest(

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

	@Schema(description = "판매 상태. 생략하면 READY", example = "READY", nullable = true)
	ProductStatus status,

	@Schema(description = "노출 여부. 생략하면 HIDDEN", example = "HIDDEN", nullable = true)
	ProductVisibility visibility,

	@Schema(description = "상품 이미지 URL (1~10장, 첫 장이 대표 이미지). media 업로드 API로 받은 URL만 허용됩니다.")
	@NotNull(message = "상품 이미지는 필수입니다.")
	@Size(min = 1, max = 10, message = "상품 이미지는 1~10장이어야 합니다.")
	List<@NotBlank(message = "이미지 URL은 비어 있을 수 없습니다.")
	@Size(max = 500, message = "이미지 URL은 500자 이하여야 합니다.") String> imageUrls,

	@Schema(description = "옵션 (1~50개, 순서대로 화면에 표시)")
	@NotNull(message = "옵션은 필수입니다.")
	@Size(min = 1, max = 50, message = "옵션은 1~50개여야 합니다.")
	List<@Valid @NotNull ProductOptionRequest> options
) {

	public ProductCreateCommand toCommand() {
		return new ProductCreateCommand(
				name, descriptionHtml, category, region, status, visibility, imageUrls,
				options.stream().map(ProductOptionRequest::toSpec).toList()
		);
	}
}
