package com.ok.product.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductQueryRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.service.dto.ProductCreateCommand;
import com.ok.product.product.service.dto.ProductUpdateCommand;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

	private static final String BUCKET_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final Long PRODUCT_ID = 10L;
	private static final Long OPTION_ID = 100L;

	@Mock
	ProductRepository productRepository;

	@Mock
	ProductOptionRepository productOptionRepository;

	@Mock
	ProductQueryRepository productQueryRepository;

	ProductService service;

	@BeforeEach
	void setUp() {
		ProductImageUrlValidator imageUrlValidator = new ProductImageUrlValidator("bucket", "ap-northeast-2");
		// ProductService가 Clock을 주입받으므로 생성자에 넣는다 (#93). 시각을 검증하는 테스트가 없어 실제 시계를 그대로 쓴다
		service = new ProductService(productRepository, imageUrlValidator, productOptionRepository,
			productQueryRepository, new ProductDescriptionSanitizer(imageUrlValidator),
			new MediaUrlResolver("bucket", "ap-northeast-2"), Clock.systemDefaultZone());
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
		ProductCreateCommand command = new ProductCreateCommand("사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, null, null, List.of("https://evil.com/a.jpg"), options());

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
		ProductCreateCommand command = new ProductCreateCommand("사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, ProductStatus.ON_SALE, ProductVisibility.VISIBLE,
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
			new ProductOptionSpec(OPTION_ID, "3kg 특품", 40_000L, 30_000L, 150),
			ProductOptionSpec.newOption("5kg", null, 49_000L, 20))));

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
		ProductUpdateCommand command = new ProductUpdateCommand(0, "사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, null, null, List.of("https://evil.com/a.jpg"), sameOption(100));

		assertErrorCode(() -> service.update(1L, PRODUCT_ID, command), ProductErrorCode.INVALID_IMAGE_URL);
	}

	// ---------- 판매 상태·노출 변경, 삭제 ----------

	@Test
	void 다른_판매자의_상품은_상태를_바꾸거나_삭제할_수_없다() {
		Product product = givenProduct(1L);

		assertErrorCode(() -> service.changeStatus(2L, PRODUCT_ID, ProductStatus.ON_SALE),
			ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertErrorCode(() -> service.changeVisibility(2L, PRODUCT_ID, ProductVisibility.VISIBLE),
			ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertErrorCode(() -> service.delete(2L, PRODUCT_ID), ProductErrorCode.PRODUCT_ACCESS_DENIED);
		assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
		assertThat(product.isDeleted()).isFalse();
	}

	// id·version이 채워진 저장된 상품처럼 만들어 수정용 조회가 돌려주게 한다
	private Product givenProduct(Long sellerId) {
		Product product = Product.create(sellerId, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, options(), List.of(BUCKET_URL + "a.jpg"));
		ReflectionTestUtils.setField(product, "id", PRODUCT_ID);
		ReflectionTestUtils.setField(product, "version", 0);
		ReflectionTestUtils.setField(product.getOptions().getFirst(), "id", OPTION_ID);
		given(productRepository.findForUpdateByIdAndDeletedAtIsNull(PRODUCT_ID)).willReturn(Optional.of(product));
		return product;
	}

	private ProductUpdateCommand updateCommand(int version, List<ProductOptionSpec> options) {
		return new ProductUpdateCommand(version, "수정한 이름", "<p>수정</p><script>alert(1)</script>",
			ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, null, null, List.of(BUCKET_URL + "b.jpg"), options);
	}

	private List<ProductOptionSpec> sameOption(int totalStock) {
		return List.of(new ProductOptionSpec(OPTION_ID, "3kg", 40_000L, 32_000L, totalStock));
	}

	private ProductCreateCommand command(String descriptionHtml) {
		return new ProductCreateCommand("청송 꿀사과", descriptionHtml, ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, null, null, List.of(BUCKET_URL + "a.jpg"), options());
	}

	private List<ProductOptionSpec> options() {
		return List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, 100));
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
