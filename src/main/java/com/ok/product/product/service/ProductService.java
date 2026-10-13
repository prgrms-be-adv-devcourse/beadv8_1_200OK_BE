package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.html.HtmlSanitizer;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
import com.ok.product.product.service.dto.ProductUpdateCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductService {

	// 정제 후 상세설명 최대 길이
	static final int MAX_DESCRIPTION_LENGTH = 100_000;

	private final ProductRepository productRepository;
	private final ProductImageUrlValidator productImageUrlValidator;
	private final ProductOptionRepository productOptionRepository;
	private final HtmlSanitizer descriptionSanitizer;

	public ProductService(ProductRepository productRepository, ProductImageUrlValidator productImageUrlValidator,
		ProductOptionRepository productOptionRepository) {
		this.productRepository = productRepository;
		this.productImageUrlValidator = productImageUrlValidator;
		this.productOptionRepository = productOptionRepository;
		this.descriptionSanitizer = new HtmlSanitizer(productImageUrlValidator::isAllowed);
	}

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
		Product product = findOwnedProduct(sellerId, productId);

		// 현재 상품 버전이 command로 넘어온 상품 버전과 다르다면 (다른 누군가의 수정이 먼저 커밋 됨)
		if (!product.getVersion().equals(command.version())) {
			throw new RestApiException(ProductErrorCode.PRODUCT_CONCURRENT_MODIFICATION);
		}

		validateImageUrls(command.imageUrls());
		String descriptionHtml = sanitizeDescription(command.descriptionHtml());

		// 상품 정보 업데이트
		product.updateInfo(command.name(), descriptionHtml, command.category(), command.region());
		product.replaceImages(command.imageUrls());
		product.mergeOptions(command.options(), now());
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

	@Transactional
	public void changeStatus(Long sellerId, Long productId, ProductStatus status) {
		Product product = findOwnedProduct(sellerId, productId);
		product.changeStatus(status);
	}

	@Transactional
	public void changeVisibility(Long sellerId, Long productId, ProductVisibility visibility) {
		Product product = findOwnedProduct(sellerId, productId);
		product.changeVisibility(visibility);
	}

	@Transactional
	public void delete(Long sellerId, Long productId) {
		Product product = findOwnedProduct(sellerId, productId);
		product.delete(now());
	}

	private void validateImageUrls(List<String> imageUrls) {
		if (!imageUrls.stream().allMatch(productImageUrlValidator::isAllowed)) {
			throw new RestApiException(ProductErrorCode.INVALID_IMAGE_URL);
		}
	}

	// 허용 태그만 남기고 본문에 있는 외부 이미지는 거절하지 않고 지운다
	private String sanitizeDescription(String descriptionHtml) {
		String sanitized = descriptionSanitizer.sanitize(descriptionHtml);
		if (sanitized.length() > MAX_DESCRIPTION_LENGTH) {
			throw new RestApiException(ProductErrorCode.DESCRIPTION_TOO_LONG);
		}
		return sanitized;
	}

	// TODO: Clock 빈이 생기면 주입받은 Clock으로 바꾼다 (service.md 현재 시각 규칙)
	private LocalDateTime now() {
		return LocalDateTime.now();
	}

	// 수정, 변경, 삭제 전에 상품을 가져오고 본인 상품인지 확인한다
	private Product findOwnedProduct(Long sellerId, Long productId) {
		Product product = productRepository.findForUpdateByIdAndDeletedAtIsNull(productId)
				.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (!product.isOwnedBy(sellerId)) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}
		return product;
	}
}
