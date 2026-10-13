package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.html.HtmlSanitizer;
import com.ok.product.product.domain.Product;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
import com.ok.product.product.service.dto.ProductUpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

	// 정제 후 상세설명 최대 길이
	static final int MAX_DESCRIPTION_LENGTH = 100_000;

	private final ProductRepository productRepository;
	private final ProductImageUrlValidator productImageUrlValidator;
	private final ProductOptionRepository productOptionRepository;

	// TODO(추후 고려): 필요시 ADMIN이 판매자 대신 등록하는 기능
	@Transactional
	public Long create(Long sellerId, ProductCreateCommand command) {
		validateImageUrls(command.imageUrls());
		String descriptionHtml = sanitizeDescription(command.descriptionHtml());

		Product product = Product.create(sellerId, command.name(), descriptionHtml, command.category(),
			command.region(), command.options(), command.imageUrls());
		if (command.status() != null) {
			product.changeStatus(command.status());
		}
		if (command.visibility() != null) {
			product.changeVisibility(command.visibility());
		}
		return productRepository.save(product).getId();
	}

	@Transactional
	public void update(Long sellerId, Long productId, ProductUpdateCommand command) {
		Product product = productRepository.findForUpdateByIdAndDeletedAtIsNull(productId).orElseThrow(() ->
				new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND)
		);

		// 해당 상품의 주인이 아니면
		if (!product.isOwnedBy(sellerId)) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}

		// 현재 상품 버전이 command로 넘어온 상품 버전과 다르다면 (다른 누군가의 수정이 먼저 커밋 됨)
		if (!product.getVersion().equals(command.version())) {
			throw new RestApiException(ProductErrorCode.PRODUCT_CONCURRENT_MODIFICATION);
		}

		validateImageUrls(command.imageUrls());
		String descriptionHtml = sanitizeDescription(command.descriptionHtml());

		// 상품 정보 업데이트
		product.updateInfo(command.name(), descriptionHtml, command.category(), command.region());
		product.replaceImages(command.imageUrls());
		product.mergeOptions(command.options());
		if (command.status() != null) {
			product.changeStatus(command.status());
		}
		if (command.visibility() != null) {
			product.changeVisibility(command.visibility());
		}

		// 옵션 재고 업데이트
		command.options().stream()
				.filter(spec -> !spec.isNew()) // isNew는 insert에 재고 등록됨
				.forEach(spec -> {
					int updated = productOptionRepository.updateTotalStock(spec.id(), spec.totalStock());

					// 현재 배정된 수량보다 작은 값이 totalStock으로 온다면
					if (updated == 0) {
						throw new RestApiException(ProductErrorCode.STOCK_BELOW_ALLOCATED);
					}
				});
	}

	private void validateImageUrls(List<String> imageUrls) {
		if (!imageUrls.stream().allMatch(productImageUrlValidator::isAllowed)) {
			throw new RestApiException(ProductErrorCode.INVALID_IMAGE_URL);
		}
	}

	// 허용 태그만 남기고 본문에 있는 외부 이미지는 거절하지 않고 지운다
	private String sanitizeDescription(String descriptionHtml) {
		String sanitized = HtmlSanitizer.sanitize(descriptionHtml, productImageUrlValidator::isAllowed);
		if (sanitized.length() > MAX_DESCRIPTION_LENGTH) {
			throw new RestApiException(ProductErrorCode.DESCRIPTION_TOO_LONG);
		}
		return sanitized;
	}
}
