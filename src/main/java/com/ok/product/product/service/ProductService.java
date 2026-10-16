package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductQueryRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
import com.ok.product.product.service.dto.ProductDetailResult;
import com.ok.product.product.service.dto.ProductOptionInfoResult;
import com.ok.product.product.service.dto.ProductSearchQuery;
import com.ok.product.product.service.dto.ProductSummaryResult;
import com.ok.product.product.service.dto.ProductUpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

	// 정제 후 상세설명 최대 길이
	static final int MAX_DESCRIPTION_LENGTH = 100_000;

	private final ProductRepository productRepository;
	private final ProductImageUrlValidator productImageUrlValidator;
	private final ProductOptionRepository productOptionRepository;
	private final ProductQueryRepository productQueryRepository;
	private final ProductDescriptionSanitizer descriptionSanitizer;
	private final MediaUrlResolver mediaUrlResolver;

	// TODO(추후 고려): 필요시 ADMIN이 판매자 대신 등록하는 기능
	@Transactional
	public Long create(Long sellerId, ProductCreateCommand command) {
		validateImageUrls(command.imageUrls());
		String descriptionHtml = sanitizeDescription(command.descriptionHtml());

		Product product = Product.create(sellerId, command.name(), descriptionHtml, command.category(),
			command.region(), command.options(), toObjectKeys(command.imageUrls()));
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
		product.replaceImages(toObjectKeys(command.imageUrls()));
		product.mergeOptions(command.options(), now());
		if (command.status() != null) {
			product.changeStatus(command.status());
		}
		if (command.visibility() != null) {
			product.changeVisibility(command.visibility());
		}

		// TODO: 재고가 그대로인 옵션도 매번 UPDATE가 나가고 updated_at이 바뀐다. 로드한 totalStock과 요청값이 다를 때만 호출하도록 바꾸기
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

	// 구매자에게 보이는 상품 목록을 최신순으로 조회한다.
	public Page<ProductSummaryResult> searchVisibleProducts(ProductSearchQuery query) {
		Page<Product> products = productQueryRepository.findVisibleProducts(query.categoryGroup(), query.category(),
			query.region(), PageRequest.of(query.page(), query.size()));
		// 품절 판정은 상세와 같은 Product.isSoldOut()을 쓴다. 옵션은 지연 로딩이지만
		// default_batch_fetch_size 덕분에 첫 접근 때 페이지 상품들의 옵션을 IN 쿼리 한 번으로 불러온다
		return products.map(product -> ProductSummaryResult.from(product, mediaUrlResolver.toUrl(product.getThumbnailKey())));
	}

	// 구매자에게 보이는 상품 상세를 조회한다.
	public ProductDetailResult getVisibleProduct(Long productId) {
		Product product = productRepository.findByIdAndVisibilityAndDeletedAtIsNull(productId, ProductVisibility.VISIBLE)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		return ProductDetailResult.from(product, mediaUrlResolver.toUrls(product.imageKeys()));
	}

	/**
	 * 옵션 ID 목록으로 옵션 정보를 한 번에 조회한다.
	 * 없는 ID는 빠지고, 숨기거나 삭제한 상품 및 옵션도 포함한다.
	 */
	public List<ProductOptionInfoResult> findOptionInfos(List<Long> optionIds) {
		return productOptionRepository.findAllWithProductByIdIn(optionIds).stream()
			.map(ProductOptionInfoResult::from)
			.toList();
	}

	private void validateImageUrls(List<String> imageUrls) {
		if (!imageUrls.stream().allMatch(productImageUrlValidator::isAllowed)) {
			throw new RestApiException(ProductErrorCode.INVALID_IMAGE_URL);
		}
	}

	private List<String> toObjectKeys(List<String> imageUrls) {
		return imageUrls.stream()
			.map(url -> mediaUrlResolver.toObjectKey(url)
				.orElseThrow(() -> new RestApiException(ProductErrorCode.INVALID_IMAGE_URL)))
			.toList();
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
