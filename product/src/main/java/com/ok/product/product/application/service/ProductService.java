package com.ok.product.product.application.service;

import com.ok.product.product.application.port.in.BuyerProductUseCase;
import com.ok.product.product.application.port.in.ProductOptionInfoUseCase;
import com.ok.product.product.application.port.in.SellerProductUseCase;
import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductCategoryGroup;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSearchLog;
import com.ok.product.product.domain.ProductSort;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.application.port.out.BuyerProductQueryRepository;
import com.ok.product.product.application.port.out.ProductOptionRepository;
import com.ok.product.product.application.port.out.ProductQueryRepository;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.application.port.out.ProductSearchLogRecorder;
import com.ok.product.product.application.dto.ProductCategoryGroupResult;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductDetailResult;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductOptionInfoResult;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSearchResult;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductSummaryResult;
import com.ok.product.product.application.dto.ProductUpdateCommand;
import com.ok.product.product.application.dto.ProductVisibilityCode;
import com.ok.product.product.application.dto.SellerProductDetailResult;
import com.ok.product.product.application.dto.SellerProductSearchQuery;
import com.ok.product.product.application.dto.SellerProductSummaryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService implements BuyerProductUseCase, SellerProductUseCase, ProductOptionInfoUseCase {

	// 정제 후 상세설명 최대 길이
	static final int MAX_DESCRIPTION_LENGTH = 100_000;

	private final ProductRepository productRepository;
	private final ProductImageUrlValidator productImageUrlValidator;
	private final ProductOptionRepository productOptionRepository;
	private final ProductQueryRepository productQueryRepository;
	private final BuyerProductQueryRepository buyerProductQueryRepository;
	private final ProductSearchPolicy productSearchPolicy;
	private final ProductSearchIndexer productSearchIndexer;
	private final ProductItemDictionary productItemDictionary;
	private final ProductDescriptionSanitizer descriptionSanitizer;
	private final MediaUrlResolver mediaUrlResolver;
	private final ProductSearchLogRecorder productSearchLogRecorder;
	// 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
	// 옵션 변경·삭제 시각을 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
	private final Clock clock;

	// TODO(추후 고려): 필요시 ADMIN이 판매자 대신 등록하는 기능
	@Override
	@Transactional
	public Long create(Long sellerId, ProductCreateCommand command) {
		validateImageUrls(command.imageUrls());
		String descriptionHtml = sanitizeDescription(command.descriptionHtml());

		Product product = Product.create(sellerId, command.name(), descriptionHtml,
			command.category().toDomain(), command.region().toDomain(), toOptionSpecs(command.options()),
			toObjectKeys(command.imageUrls()));
		if (command.status() != null) {
			product.changeStatus(command.status().toDomain());
		}
		if (command.visibility() != null) {
			product.changeVisibility(command.visibility().toDomain());
		}
		Product saved = productRepository.save(product);
		productSearchIndexer.index(saved);
		return saved.getId();
	}

	@Override
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
		product.updateInfo(command.name(), descriptionHtml, command.category().toDomain(), command.region().toDomain());
		product.replaceImages(toObjectKeys(command.imageUrls()));
		List<ProductOptionSpec> optionSpecs = toOptionSpecs(command.options());
		product.mergeOptions(optionSpecs, now());
		if (command.status() != null) {
			product.changeStatus(command.status().toDomain());
		}
		if (command.visibility() != null) {
			product.changeVisibility(command.visibility().toDomain());
		}

		// TODO: 재고가 그대로인 옵션도 매번 UPDATE가 나가고 updated_at이 바뀐다. 로드한 totalStock과 요청값이 다를 때만 호출하도록 바꾸기
		// 옵션 재고 업데이트
		optionSpecs.stream()
				.filter(spec -> !spec.isNew()) // isNew는 insert에 재고 등록됨
				.forEach(spec -> {
					int updated = productOptionRepository.updateTotalStock(spec.id(), spec.totalStock());

					// 현재 배정된 수량보다 작은 값이 totalStock으로 온다면
					if (updated == 0) {
						throw new RestApiException(ProductErrorCode.STOCK_BELOW_ALLOCATED);
					}
				});

		// 가격·재고만 바뀌어도 다시 계산한다. 바뀐 필드를 가리는 조건이 계산 비용보다 버그를 만들기 쉽다
		productSearchIndexer.index(product);
	}

	@Override
	@Transactional
	public void changeStatus(Long sellerId, Long productId, ProductStatusCode status) {
		Product product = findOwnedProduct(sellerId, productId);
		product.changeStatus(status.toDomain());
	}

	@Override
	@Transactional
	public void changeVisibility(Long sellerId, Long productId, ProductVisibilityCode visibility) {
		Product product = findOwnedProduct(sellerId, productId);
		product.changeVisibility(visibility.toDomain());
	}

	@Override
	@Transactional
	public void delete(Long sellerId, Long productId) {
		Product product = findOwnedProduct(sellerId, productId);
		product.delete(now());
	}

	@Override
	public ProductSearchResult searchVisibleProducts(ProductSearchQuery query) {
		ProductSearchKeyword keyword = ProductSearchKeyword.from(query.keyword()).orElse(null);
		long startedAt = System.nanoTime();
		Page<ProductSummaryResult> products = findVisibleProducts(query, keyword);
		long latencyMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
		// 결과 수와 관계없이 제안한다. 결과가 적을 때만 제안하면 "고추가루"처럼 고추 상품이 잘 찾히는 오타에서 제안이 사라진다
		String suggestion = productItemDictionary.suggest(query.keyword()).orElse(null);
		// 다음 페이지를 넘길 때마다 남기면 같은 검색이 여러 번 세어져 0건 검색어 비율이 틀어진다
		if (keyword != null && query.page() == 0) {
			productSearchLogRecorder.record(ProductSearchLog.of(query.keyword(), keyword, products.getTotalElements(),
				latencyMillis, suggestion != null, now()));
		}
		return new ProductSearchResult(products, suggestion);
	}

	// 구매자에게 보이는 상품 목록을 조회한다. 정렬을 고르지 않으면 검색어가 있을 때 정확도순, 없을 때 최신순이고 품절 상품은 뒤로 간다
	private Page<ProductSummaryResult> findVisibleProducts(ProductSearchQuery query, ProductSearchKeyword keyword) {
		ProductCategoryGroup categoryGroup = query.categoryGroup() == null ? null : query.categoryGroup().toDomain();
		ProductCategory category = query.category() == null ? null : query.category().toDomain();
		ProductRegion region = query.region() == null ? null : query.region().toDomain();
		PageRequest pageable = PageRequest.of(query.page(), query.size());
		ProductSort sort = ProductSort.resolve(keyword != null, query.sort() == null ? null : query.sort().toDomain());

		// 검색 SQL은 순서만 정해 ID 한 페이지를 주고, 상품은 엔티티로 다시 불러온다. 응답 값을 상세와 같은 코드
		// (Product, ProductSummaryResult)로 만들어야 카드 값이 어긋나지 않는다
		Page<Long> productIds;
		try {
			productIds = buyerProductQueryRepository.findVisibleProductIds(keyword, categoryGroup, category, region, sort,
				productSearchPolicy.minScore(), pageable);
		} catch (QueryTimeoutException e) {
			// RestApiException은 공통 핸들러가 로그를 남기지 않아 여기서 남긴다. 느린 조건을 찾을 수 있게 모양만 남기고,
			// 검색어 원문은 개인정보가 섞일 수 있어 남기지 않는다
			log.warn("상품 목록 조회가 시간 제한을 넘었습니다. keywordLength={}, sort={}, categoryGroup={}, category={}, region={}",
				query.keyword() == null ? 0 : query.keyword().length(), sort, categoryGroup != null, category != null,
				region != null);
			throw new RestApiException(ProductErrorCode.PRODUCT_SEARCH_TIMEOUT);
		}
		Map<Long, Product> productsById = productRepository.findAllById(productIds.getContent()).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity()));
		// 저장소는 ID 순서를 지키지 않으므로 검색 SQL이 정한 순서로 다시 맞춘다.
		// 응답의 품절 표시는 상세와 같은 Product.isSoldOut()을 쓴다. 옵션은 지연 로딩이지만
		// default_batch_fetch_size 덕분에 첫 접근 때 페이지 상품들의 옵션을 IN 쿼리 한 번으로 불러온다
		return productIds.map(productsById::get)
			.map(product -> ProductSummaryResult.from(product, mediaUrlResolver.toUrl(product.getThumbnailKey())));
	}

	// 판매자 본인의 상품 목록을 최신순으로 조회한다. 관리 화면용이라 숨긴 상품도 나오고, 삭제한 상품만 빠진다
	@Override
	public Page<SellerProductSummaryResult> searchMyProducts(Long sellerId, SellerProductSearchQuery query) {
		Page<Product> products = productQueryRepository.findSellerProducts(
			sellerId,
			query.status() == null ? null : query.status().toDomain(),
			query.visibility() == null ? null : query.visibility().toDomain(),
			PageRequest.of(query.page(), query.size()));

		return products.map(product -> SellerProductSummaryResult.from(product, mediaUrlResolver.toUrl(product.getThumbnailKey())));
	}

	// 판매자 수정 화면용 상세. findOwnedProduct는 version을 올리는 락을 걸어서 쓰지 않는다
	@Override
	public SellerProductDetailResult getMyProduct(Long sellerId, Long productId) {
		Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (!product.isOwnedBy(sellerId)) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}
		return SellerProductDetailResult.from(product, mediaUrlResolver.toUrls(product.imageKeys()));
	}

	// 구매자에게 보이는 상품 상세를 조회한다.
	@Override
	public ProductDetailResult getVisibleProduct(Long productId) {
		Product product = productRepository.findByIdAndVisibilityAndDeletedAtIsNull(productId, ProductVisibility.VISIBLE)
			.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		return ProductDetailResult.from(product, mediaUrlResolver.toUrls(product.imageKeys()));
	}

	// 목록 조회 필터에 쓰는 상위·하위 카테고리. DB를 거치지 않고 enum 정의에서 만든다
	@Override
	public List<ProductCategoryGroupResult> getCategories() {
		return Arrays.stream(ProductCategoryGroup.values())
			.map(ProductCategoryGroupResult::from)
			.toList();
	}

	@Override
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

	private List<ProductOptionSpec> toOptionSpecs(List<ProductOptionCommand> options) {
		return options.stream().map(ProductOptionCommand::toSpec).toList();
	}

	// 허용 태그만 남기고 본문에 있는 외부 이미지는 거절하지 않고 지운다
	private String sanitizeDescription(String descriptionHtml) {
		String sanitized = descriptionSanitizer.sanitize(descriptionHtml);
		if (sanitized.length() > MAX_DESCRIPTION_LENGTH) {
			throw new RestApiException(ProductErrorCode.DESCRIPTION_TOO_LONG);
		}
		return sanitized;
	}

	// 상품 수정·삭제와 검색어 로그에서 쓰는 현재 시각. 주입받은 Clock 기준 (#93에서 Clock 빈이 생겨 TODO 해결)
	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	// 수정, 변경, 삭제 전에 상품을 가져오고 본인 상품인지 확인한다
	// TODO(논의 필요): ADMIN이 다른 판매자의 상품을 수정·삭제할 수 있어야 하는지 미정
	private Product findOwnedProduct(Long sellerId, Long productId) {
		Product product = productRepository.findForUpdateByIdAndDeletedAtIsNull(productId)
				.orElseThrow(() -> new RestApiException(ProductErrorCode.PRODUCT_NOT_FOUND));
		if (!product.isOwnedBy(sellerId)) {
			throw new RestApiException(ProductErrorCode.PRODUCT_ACCESS_DENIED);
		}
		return product;
	}
}
