package com.ok.product.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSearchLog;
import com.ok.product.product.domain.ProductSort;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.application.port.out.BuyerProductQueryRepository;
import com.ok.product.product.application.port.out.ProductOptionRepository;
import com.ok.product.product.application.port.out.ProductQueryRepository;
import com.ok.product.product.application.port.out.ProductRepository;
import com.ok.product.product.application.port.out.ProductSearchLogRecorder;
import com.ok.product.product.application.port.out.ProductTagJudgmentRepository;
import com.ok.product.product.application.dto.ProductCategoryCode;
import com.ok.product.product.application.dto.ProductCreateCommand;
import com.ok.product.product.application.dto.ProductOptionCommand;
import com.ok.product.product.application.dto.ProductRegionCode;
import com.ok.product.product.application.dto.ProductSearchQuery;
import com.ok.product.product.application.dto.ProductSearchResult;
import com.ok.product.product.application.dto.ProductSortCode;
import com.ok.product.product.application.dto.ProductStatusCode;
import com.ok.product.product.application.dto.ProductSummaryResult;
import com.ok.product.product.application.dto.ProductUpdateCommand;
import com.ok.product.product.application.dto.ProductVisibilityCode;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final String BUCKET_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final Long PRODUCT_ID = 10L;
	private static final Long OPTION_ID = 100L;
	private static final double MIN_SCORE = 1.5;
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneId.of("Asia/Seoul"));

	@Mock
	ProductRepository productRepository;

	@Mock
	ProductOptionRepository productOptionRepository;

	@Mock
	ProductQueryRepository productQueryRepository;

	@Mock
	BuyerProductQueryRepository buyerProductQueryRepository;

	@Mock
	ProductSearchIndexer productSearchIndexer;

	@Mock
	ProductSearchLogRecorder productSearchLogRecorder;

	@Mock
	ProductTagJudgmentRepository productTagJudgmentRepository;

	ProductService service;

	@BeforeEach
	void setUp() {
		ProductImageUrlValidator imageUrlValidator = new ProductImageUrlValidator("bucket", "ap-northeast-2");
		service = new ProductService(productRepository, imageUrlValidator, productOptionRepository,
			productQueryRepository, buyerProductQueryRepository, new ProductSearchPolicy(MIN_SCORE), productSearchIndexer, new ProductItemDictionary(),
			new ProductDescriptionSanitizer(imageUrlValidator),
			new MediaUrlResolver("bucket", "ap-northeast-2"), productSearchLogRecorder, productTagJudgmentRepository, CLOCK);
	}

	@Test
	void 요청한_판매자의_상품으로_등록된다() {
		givenSaveAssignsId(10L);

		Long productId = service.create(1L, command("<p>설명</p>"));

		assertThat(productId).isEqualTo(10L);
		assertThat(savedProduct().getSellerId()).isEqualTo(1L);
	}

	@Test
	void 업로드하지_않은_이미지_URL이면_등록할_수_없다() {
		ProductCreateCommand command = new ProductCreateCommand("사과", "<p>설명</p>", ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, null, null, List.of("https://evil.com/a.jpg"), options());

		assertErrorCode(() -> service.create(1L, command),
			ProductErrorCode.INVALID_IMAGE_URL);
	}

	@Test
	void 상세설명은_정제해서_저장한다() {
		givenSaveAssignsId(10L);

		service.create(1L,
			command("<p>설명</p><script>alert(1)</script><img src=\"https://evil.com/x.jpg\">"));

		assertThat(savedProduct().getDescriptionHtml()).isEqualTo("<p>설명</p>");
	}

	@Test
	void 정제_후에도_너무_길면_등록할_수_없다() {
		String longDescription = "<p>" + "가".repeat(ProductService.MAX_DESCRIPTION_LENGTH) + "</p>";

		assertErrorCode(() -> service.create(1L, command(longDescription)),
			ProductErrorCode.DESCRIPTION_TOO_LONG);
	}

	@Test
	void 판매_상태와_노출을_보내면_그대로_등록한다() {
		givenSaveAssignsId(10L);
		ProductCreateCommand command = new ProductCreateCommand("사과", "<p>설명</p>", ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, ProductStatusCode.ON_SALE, ProductVisibilityCode.VISIBLE,
			List.of(BUCKET_URL + "a.jpg"), options());

		service.create(1L, command);

		assertThat(savedProduct().getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(savedProduct().getVisibility()).isEqualTo(ProductVisibility.VISIBLE);
	}

	// ---------- 수정 ----------

	@Test
	void 수정하면_정보_이미지_옵션과_기존_옵션_재고가_반영된다() {
		Product product = givenProduct(1L);
		given(productOptionRepository.updateTotalStock(OPTION_ID, 150)).willReturn(1);

		service.update(1L, PRODUCT_ID, updateCommand(0, List.of(
			new ProductOptionCommand(OPTION_ID, "3kg 특품", 40_000L, 30_000L, 150),
			new ProductOptionCommand(null, "5kg", null, 49_000L, 20))));

		assertThat(product.getName()).isEqualTo("수정한 이름");
		assertThat(product.getDescriptionHtml()).isEqualTo("<p>수정</p>");
		assertThat(product.getThumbnailKey()).isEqualTo("product/b.jpg");
		assertThat(product.activeOptions()).extracting(ProductOption::getName).containsExactly("3kg 특품", "5kg");
		assertThat(product.getMinSalePrice()).isEqualTo(30_000L);
		// 새 옵션은 INSERT로 재고가 들어가므로 재고 변경 쿼리는 기존 옵션 1번만
		verify(productOptionRepository, times(1)).updateTotalStock(anyLong(), anyInt());
	}

	@Test
	void 수정할_때_판매_상태와_노출을_생략하면_그대로_둔다() {
		Product product = givenProduct(1L);
		product.changeStatus(ProductStatus.ON_SALE);
		given(productOptionRepository.updateTotalStock(eq(OPTION_ID), anyInt())).willReturn(1);

		service.update(1L, PRODUCT_ID, updateCommand(0, sameOption(100)));

		assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
		assertThat(product.getVisibility()).isEqualTo(ProductVisibility.HIDDEN);
	}

	@Test
	void 없는_상품은_수정할_수_없다() {
		given(productRepository.findForUpdateByIdAndDeletedAtIsNull(PRODUCT_ID)).willReturn(Optional.empty());

		assertErrorCode(() -> service.update(1L, PRODUCT_ID, updateCommand(0, sameOption(100))),
			ProductErrorCode.PRODUCT_NOT_FOUND);
	}

	@Test
	void 다른_판매자의_상품은_수정할_수_없다() {
		givenProduct(1L);

		assertErrorCode(() -> service.update(2L, PRODUCT_ID, updateCommand(0, sameOption(100))),
			ProductErrorCode.PRODUCT_ACCESS_DENIED);
	}

	@Test
	void version이_다르면_수정할_수_없고_아무것도_바꾸지_않는다() {
		Product product = givenProduct(1L);

		assertErrorCode(() -> service.update(1L, PRODUCT_ID, updateCommand(3, sameOption(100))),
			ProductErrorCode.PRODUCT_CONCURRENT_MODIFICATION);
		assertThat(product.getName()).isEqualTo("청송 꿀사과");
		verify(productOptionRepository, never()).updateTotalStock(anyLong(), anyInt());
	}

	@Test
	void 총재고를_배정_수량보다_적게_줄이면_수정할_수_없다() {
		givenProduct(1L);
		given(productOptionRepository.updateTotalStock(OPTION_ID, 1)).willReturn(0);

		assertErrorCode(() -> service.update(1L, PRODUCT_ID, updateCommand(0, sameOption(1))),
			ProductErrorCode.STOCK_BELOW_ALLOCATED);
	}

	@Test
	void 수정할_때도_업로드하지_않은_이미지_URL이면_거부한다() {
		givenProduct(1L);
		ProductUpdateCommand command = new ProductUpdateCommand(0, "사과", "<p>설명</p>", ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, null, null, List.of("https://evil.com/a.jpg"), sameOption(100));

		assertErrorCode(() -> service.update(1L, PRODUCT_ID, command), ProductErrorCode.INVALID_IMAGE_URL);
	}

	// ---------- 판매 상태·노출 변경, 삭제 ----------

	@Test
	void 다른_판매자의_상품은_상태를_바꾸거나_삭제할_수_없다() {
		Product product = givenProduct(1L);

		assertErrorCode(() -> service.changeStatus(2L, PRODUCT_ID, ProductStatusCode.ON_SALE),
			ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertErrorCode(() -> service.changeVisibility(2L, PRODUCT_ID, ProductVisibilityCode.VISIBLE),
			ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertErrorCode(() -> service.delete(2L, PRODUCT_ID), ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
		assertThat(product.isDeleted()).isFalse();
	}

	// ---------- 구매자 목록 ----------

	@ParameterizedTest
	@MethodSource("검색어가_없는_것으로_보는_값")
	void 검색어가_비었거나_기호뿐이면_검색어_없이_최신순으로_찾고_검색어_로그를_남기지_않는다(String keyword) {
		// given
		PageRequest pageable = PageRequest.of(0, 20);
		given(buyerProductQueryRepository.findVisibleProductIds(null, null, null, null, ProductSort.LATEST, MIN_SCORE,
			pageable)).willReturn(new PageImpl<>(List.of(PRODUCT_ID), pageable, 1));
		given(productRepository.findAllById(List.of(PRODUCT_ID))).willReturn(List.of(productWithId(PRODUCT_ID)));

		// when
		Page<ProductSummaryResult> sut = service.searchVisibleProducts(
			new ProductSearchQuery(keyword, null, null, null, null, 0, 20)).products();

		// then
		assertThat(sut.getContent()).extracting(ProductSummaryResult::productId).containsExactly(PRODUCT_ID);
		then(productSearchLogRecorder).should(never()).record(any());
	}

	static Stream<String> 검색어가_없는_것으로_보는_값() {
		return Stream.of(null, "", "   ", "@@@", "!!!");
	}

	@Test
	void 검색어_없이_정확도순을_요청하면_최신순으로_찾는다() {
		// given
		PageRequest pageable = PageRequest.of(0, 20);
		given(buyerProductQueryRepository.findVisibleProductIds(null, null, null, null, ProductSort.LATEST, MIN_SCORE,
			pageable)).willReturn(new PageImpl<>(List.of(), pageable, 0));

		// when
		Page<ProductSummaryResult> sut = service.searchVisibleProducts(
			new ProductSearchQuery(null, null, null, null, ProductSortCode.RELEVANCE, 0, 20)).products();

		// then
		assertThat(sut.getContent()).isEmpty();
	}

	@Test
	void 검색어가_있으면_정규화한_검색어와_정렬과_하한으로_찾고_검색_결과의_순서대로_돌려주며_첫_페이지가_아니면_로그를_남기지_않는다() {
		// given
		PageRequest pageable = PageRequest.of(1, 3);
		given(buyerProductQueryRepository.findVisibleProductIds(any(), eq(null), eq(ProductCategory.FRUIT),
			eq(ProductRegion.JEJU), eq(ProductSort.PRICE_ASC), eq(MIN_SCORE), eq(pageable)))
			.willReturn(new PageImpl<>(List.of(30L, 10L, 20L), pageable, 7));
		// 저장소는 ID 순서를 지키지 않는다
		given(productRepository.findAllById(List.of(30L, 10L, 20L)))
			.willReturn(List.of(productWithId(10L), productWithId(20L), productWithId(30L)));

		// when
		Page<ProductSummaryResult> sut = service.searchVisibleProducts(new ProductSearchQuery("샤인 머스캣!", null,
			ProductCategoryCode.FRUIT, ProductRegionCode.JEJU, ProductSortCode.PRICE_ASC, 1, 3)).products();

		// then
		assertThat(sut.getContent()).extracting(ProductSummaryResult::productId).containsExactly(30L, 10L, 20L);
		assertThat(sut.getTotalElements()).isEqualTo(7);
		ArgumentCaptor<ProductSearchKeyword> captor = ArgumentCaptor.forClass(ProductSearchKeyword.class);
		then(buyerProductQueryRepository).should()
			.findVisibleProductIds(captor.capture(), any(), any(), any(), any(), anyDouble(), any());
		assertThat(captor.getValue()).isEqualTo(new ProductSearchKeyword("샤인 머스캣", "샤인머스캣", "ㅅㅑㅇㅣㄴㅁㅓㅅㅡㅋㅐㅅ", false));
		then(productSearchLogRecorder).should(never()).record(any());
	}

	@Test
	void 검색어가_있는_첫_페이지는_원문과_정규화한_검색어와_전체_결과_수와_제안_여부로_로그를_남긴다() {
		// given
		PageRequest pageable = PageRequest.of(0, 2);
		given(buyerProductQueryRepository.findVisibleProductIds(any(), any(), any(), any(), any(), anyDouble(),
			eq(pageable))).willReturn(new PageImpl<>(List.of(10L, 20L), pageable, 5));
		given(productRepository.findAllById(List.of(10L, 20L)))
			.willReturn(List.of(productWithId(10L), productWithId(20L)));

		// when
		ProductSearchResult result = service.searchVisibleProducts(
			new ProductSearchQuery("고추 가루", null, null, null, null, 0, 2));

		// then
		assertThat(result.suggestion()).isEqualTo("고춧가루");
		ArgumentCaptor<ProductSearchLog> captor = ArgumentCaptor.forClass(ProductSearchLog.class);
		then(productSearchLogRecorder).should().record(captor.capture());
		ProductSearchLog sut = captor.getValue();
		assertThat(sut).isNotNull();
		assertSoftly(softly -> {
			softly.assertThat(sut.getKeyword()).isEqualTo("고추 가루");
			softly.assertThat(sut.getNormalizedKeyword()).isEqualTo("고추가루");
			softly.assertThat(sut.getResultCount()).isEqualTo(5);
			softly.assertThat(sut.getLatencyMillis()).isNotNegative();
			softly.assertThat(sut.isSuggested()).isTrue();
			softly.assertThat(sut.getSearchedAt()).isEqualTo(LocalDateTime.now(CLOCK));
		});
	}

	@Test
	void 검색어가_있고_정렬을_고르지_않으면_정확도순으로_찾는다() {
		// given
		PageRequest pageable = PageRequest.of(0, 20);
		given(buyerProductQueryRepository.findVisibleProductIds(any(), eq(null), eq(null), eq(null),
			eq(ProductSort.RELEVANCE), eq(MIN_SCORE), eq(pageable))).willReturn(new PageImpl<>(List.of(), pageable, 0));

		// when
		Page<ProductSummaryResult> sut = service.searchVisibleProducts(
			new ProductSearchQuery("사과", null, null, null, null, 0, 20)).products();

		// then
		assertThat(sut.getContent()).isEmpty();
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void 목록_SQL이_시간_제한을_넘으면_PRODUCT_SEARCH_TIMEOUT이고_로그에_검색어_원문을_남기지_않으며_검색어_로그도_남기지_않는다(CapturedOutput output) {
		// given
		String keyword = "제주 하우스감귤";
		given(buyerProductQueryRepository.findVisibleProductIds(any(), any(), any(), any(), any(), anyDouble(), any()))
			.willThrow(new QueryTimeoutException("Statement cancelled due to timeout"));

		// when & then
		assertThatThrownBy(() -> service.searchVisibleProducts(
			new ProductSearchQuery(keyword, null, ProductCategoryCode.FRUIT, null, ProductSortCode.PRICE_ASC, 0, 20)))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_SEARCH_TIMEOUT));
		assertThat(output).contains("keywordLength=8", "sort=PRICE_ASC", "category=true", "region=false")
			.doesNotContain(keyword, "하우스감귤");
		then(productRepository).should(never()).findAllById(any());
		then(productSearchLogRecorder).should(never()).record(any());
	}

	// id·version이 채워진 저장된 상품처럼 만들어 수정용 조회가 돌려주게 한다
	private Product givenProduct(Long sellerId) {
		Product product = Product.create(sellerId, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100)),
			List.of(BUCKET_URL + "a.jpg"));
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "version", 0);
		ReflectionTestUtils.setField(product.getOptions().getFirst(), "id", OPTION_ID);
		given(productRepository.findForUpdateByIdAndDeletedAtIsNull(PRODUCT_ID)).willReturn(Optional.of(product));
		return product;
	}

	private Product productWithId(Long id) {
		Product product = Product.create(1L, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT, ProductRegion.GYEONGBUK,
			List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100)), List.of("product/a.jpg"));
		ReflectionTestUtils.setField(product, "id", id);
		return product;
	}

	private ProductUpdateCommand updateCommand(int version, List<ProductOptionCommand> options) {
		return new ProductUpdateCommand(version, "수정한 이름", "<p>수정</p><script>alert(1)</script>",
			ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, null, null, List.of(BUCKET_URL + "b.jpg"), options);
	}

	private List<ProductOptionCommand> sameOption(int totalStock) {
		return List.of(new ProductOptionCommand(OPTION_ID, "3kg", 40_000L, 32_000L, totalStock));
	}

	private ProductCreateCommand command(String descriptionHtml) {
		return new ProductCreateCommand("청송 꿀사과", descriptionHtml, ProductCategoryCode.FRUIT,
			ProductRegionCode.GYEONGBUK, null, null, List.of(BUCKET_URL + "a.jpg"), options());
	}

	private List<ProductOptionCommand> options() {
		return List.of(new ProductOptionCommand(null, "3kg", 40_000L, 32_000L, 100));
	}

	private void givenSaveAssignsId(Long id) {
		given(productRepository.save(any(Product.class))).willAnswer(invocation -> {
			Product product = invocation.getArgument(0);
			ReflectionTestUtils.setField(product, "id", id);
			return product;
		});
	}

	private Product savedProduct() {
		ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
		verify(productRepository).save(captor.capture());
		return captor.getValue();
	}

	private void assertErrorCode(Runnable call, ProductErrorCode expected) {
		assertThatThrownBy(call::run)
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(expected);
		verify(productRepository, never()).save(any());
	}
}
