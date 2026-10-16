package com.ok.product.product.service;

import com.ok.common.exception.RestApiException;
import com.ok.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductQueryRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
import com.ok.product.product.service.dto.ProductDetailResult;
import com.ok.product.product.service.dto.ProductSearchQuery;
import com.ok.product.product.service.dto.ProductSummaryResult;
import com.ok.product.product.service.dto.ProductUpdateCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
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
	// 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
	// 옵션 변경·삭제 시각을 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
	private final Clock clock;

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

	// 상품 수정·삭제에서 쓰는 현재 시각. 주입받은 Clock 기준 (#93에서 Clock 빈이 생겨 TODO 해결)
	private LocalDateTime now() {
		return LocalDateTime.now(clock);
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
