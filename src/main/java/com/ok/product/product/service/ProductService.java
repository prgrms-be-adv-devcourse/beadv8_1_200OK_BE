package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.html.HtmlSanitizer;
import com.ok.product.product.domain.Product;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
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

	private void validateImageUrls(List<String> imageUrls) {
		if (!imageUrls.stream().allMatch(productImageUrlValidator::isAllowed)) {
			throw new RestApiException(ProductErrorCode.INVALID_IMAGE_URL);
		}
	}

	// 허용 태그만 남기고 본문에 있는 외부 이미지는 거절하지 않고 지운다.
	private String sanitizeDescription(String descriptionHtml) {
		String sanitized = HtmlSanitizer.sanitize(descriptionHtml, productImageUrlValidator::isAllowed);
		if (sanitized.length() > MAX_DESCRIPTION_LENGTH) {
			throw new RestApiException(ProductErrorCode.DESCRIPTION_TOO_LONG);
		}
		return sanitized;
	}
}
