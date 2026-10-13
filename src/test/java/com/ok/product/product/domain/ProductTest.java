package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.exception.ProductErrorCode;

class ProductTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 12, 0);
	private static final List<String> IMAGES = List.of("https://bucket/product/a.jpg", "https://bucket/product/b.jpg");

	@Test
	void 상품을_만들면_판매_준비_숨김_상태로_시작한다() {
		Product product = create(List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10)));

		assertThat(product.getStatus()).isEqualTo(ProductStatus.READY);
		assertThat(product.getVisibility()).isEqualTo(ProductVisibility.HIDDEN);
		assertThat(product.getThumbnailUrl()).isEqualTo(IMAGES.get(0));
		assertThat(product.getImages()).hasSize(2);
		assertThat(product.getOptions()).allSatisfy(option -> assertThat(option.getProduct()).isSameAs(product));
	}

	@Test
	void 옵션이_없으면_만들_수_없다() {
		assertThatThrownBy(() -> create(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("옵션은 1개 이상이어야 합니다.");
	}

	@Test
	void 이미지가_없으면_만들_수_없다() {
		assertThatThrownBy(() -> Product.create(1L, "고구마", "<p>설명</p>", ProductCategory.ROOT_CROP,
			ProductRegion.JEONNAM, List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10)), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 옵션명이_겹치면_만들_수_없다() {
		assertThatThrownBy(() -> create(List.of(
			ProductOptionSpec.newOption("3kg", null, 30_000L, 10),
			ProductOptionSpec.newOption("3kg", null, 35_000L, 10))))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.DUPLICATE_OPTION_NAME);
	}

	@Test
	void 옵션명은_앞뒤_공백을_지우고_저장하며_공백만_다르면_중복이다() {
		Product product = create(List.of(ProductOptionSpec.newOption(" 3kg ", null, 30_000L, 10)));
		assertThat(product.getOptions().getFirst().getName()).isEqualTo("3kg");

		assertThatThrownBy(() -> create(List.of(
			ProductOptionSpec.newOption("3kg", null, 30_000L, 10),
			ProductOptionSpec.newOption("3kg ", null, 35_000L, 10))))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.DUPLICATE_OPTION_NAME);
	}

	@Test
	void 대표_가격은_최저_판매가_옵션의_판매가와_정가다() {
		Product product = create(List.of(
			ProductOptionSpec.newOption("5kg", 35_000L, 35_000L, 10),
			ProductOptionSpec.newOption("3kg", 50_000L, 30_000L, 10)));

		assertThat(product.getMinSalePrice()).isEqualTo(30_000L);
		assertThat(product.getMinPriceListPrice()).isEqualTo(50_000L);
	}

	@Test
	void 최저_판매가가_같으면_앞_순서_옵션의_정가를_쓴다() {
		Product product = create(List.of(
			ProductOptionSpec.newOption("A", 40_000L, 30_000L, 10),
			ProductOptionSpec.newOption("B", 50_000L, 30_000L, 10)));

		assertThat(product.getMinPriceListPrice()).isEqualTo(40_000L);
	}

	@Test
	void 옵션_병합은_수정_삭제_추가를_한번에_반영하고_최저가를_다시_계산한다() {
		Product product = create(List.of(
			ProductOptionSpec.newOption("A", null, 30_000L, 10),
			ProductOptionSpec.newOption("B", null, 20_000L, 10)));
		ProductOption a = assignIds(product).get(0);
		ProductOption b = product.getOptions().get(1);

		product.mergeOptions(List.of(
			new ProductOptionSpec(a.getId(), "A 특품", 40_000L, 35_000L, 10),
			ProductOptionSpec.newOption("C", null, 25_000L, 5)), NOW);

		assertThat(a.getName()).isEqualTo("A 특품");
		assertThat(a.getSalePrice()).isEqualTo(35_000L);
		assertThat(b.isDeleted()).isTrue();
		assertThat(product.activeOptions()).extracting(ProductOption::getName).containsExactly("A 특품", "C");
		assertThat(product.getMinSalePrice()).isEqualTo(25_000L);
	}

	@Test
	void 병합_후_삭제된_옵션과_같은_이름의_옵션을_추가할_수_있다() {
		Product product = create(List.of(
			ProductOptionSpec.newOption("A", null, 30_000L, 10),
			ProductOptionSpec.newOption("B", null, 20_000L, 10)));
		ProductOption a = assignIds(product).get(0);

		product.mergeOptions(List.of(new ProductOptionSpec(a.getId(), "A", null, 30_000L, 10)), NOW);
		product.mergeOptions(List.of(
			new ProductOptionSpec(a.getId(), "A", null, 30_000L, 10),
			ProductOptionSpec.newOption("B", null, 22_000L, 10)), NOW);

		assertThat(product.activeOptions()).extracting(ProductOption::getName).containsExactly("A", "B");
		assertThat(product.getOptions()).hasSize(3);
	}

	@Test
	void 다른_상품의_옵션_id로는_병합할_수_없다() {
		Product product = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 10)));
		assignIds(product);

		assertThatThrownBy(() -> product.mergeOptions(List.of(new ProductOptionSpec(999L, "A", null, 30_000L, 10)), NOW))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.OPTION_NOT_FOUND);
	}

	@Test
	void 같은_옵션_id를_두_번_보내면_병합할_수_없다() {
		Product product = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 10)));
		ProductOption a = assignIds(product).get(0);

		assertThatThrownBy(() -> product.mergeOptions(List.of(
			new ProductOptionSpec(a.getId(), "A", null, 30_000L, 10),
			new ProductOptionSpec(a.getId(), "A2", null, 30_000L, 10)), NOW))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.OPTION_NOT_FOUND);
	}

	@Test
	void 이미지를_교체하면_순서대로_다시_저장되고_대표_이미지가_바뀐다() {
		Product product = create(List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10)));

		product.replaceImages(List.of("https://bucket/product/c.jpg", "https://bucket/product/a.jpg"));

		assertThat(product.getImages()).extracting(ProductImage::getUrl)
			.containsExactly("https://bucket/product/c.jpg", "https://bucket/product/a.jpg");
		assertThat(product.getImages()).extracting(ProductImage::getSortOrder).containsExactly(0, 1);
		assertThat(product.getThumbnailUrl()).isEqualTo("https://bucket/product/c.jpg");
	}

	@Test
	void 같은_이미지를_같은_순서로_보내면_이미지를_다시_만들지_않는다() {
		Product product = create(List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, 10)));
		List<ProductImage> before = List.copyOf(product.getImages());

		product.replaceImages(IMAGES);

		assertThat(product.getImages()).containsExactlyElementsOf(before);
		product.replaceImages(List.of(IMAGES.get(1), IMAGES.get(0)));
		assertThat(product.getImages()).doesNotContainAnyElementsOf(before);
	}

	@Test
	void 삭제하면_상품과_모든_옵션에_삭제_표시가_남는다() {
		Product product = create(List.of(
			ProductOptionSpec.newOption("A", null, 30_000L, 10),
			ProductOptionSpec.newOption("B", null, 20_000L, 10)));

		product.delete(NOW);

		assertThat(product.getDeletedAt()).isEqualTo(NOW);
		assertThat(product.getOptions()).extracting(ProductOption::getDeletedAt).containsOnly(NOW);
	}

	@Test
	void 활성_옵션의_판매_가능_수량이_모두_0이면_품절이다() {
		Product inStock = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 1)));
		Product soldOut = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 0)));

		assertThat(inStock.isSoldOut()).isFalse();
		assertThat(soldOut.isSoldOut()).isTrue();
	}

	@Test
	void 리뷰가_없으면_평균_별점은_0이고_있으면_소수_첫째_자리까지() {
		Product product = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 10)));
		assertThat(product.averageRating()).isZero();

		ReflectionTestUtils.setField(product, "reviewCount", 3);
		ReflectionTestUtils.setField(product, "ratingSum", 13);
		assertThat(product.averageRating()).isEqualTo(4.3);
	}

	@Test
	void 옵션_목록은_밖에서_수정할_수_없다() {
		Product product = create(List.of(ProductOptionSpec.newOption("A", null, 30_000L, 10)));

		assertThatThrownBy(() -> product.getOptions().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private Product create(List<ProductOptionSpec> options) {
		return Product.create(1L, "꿀고구마", "<p>설명</p>", ProductCategory.ROOT_CROP, ProductRegion.JEONNAM,
			options, IMAGES);
	}

	// DB 없이 병합을 테스트하려고 저장된 것처럼 옵션에 id를 넣는다
	private List<ProductOption> assignIds(Product product) {
		List<ProductOption> options = product.getOptions();
		for (int i = 0; i < options.size(); i++) {
			ReflectionTestUtils.setField(options.get(i), "id", (long)(i + 1));
		}
		return options;
	}
}
