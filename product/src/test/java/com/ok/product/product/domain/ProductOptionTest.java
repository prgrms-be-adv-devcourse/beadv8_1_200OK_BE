package com.ok.product.product.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.exception.ProductErrorCode;

class ProductOptionTest {

	@Test
	void 정가가_없으면_판매가로_채운다() {
		ProductOption option = ProductOption.create(null, "3kg", null, 30_000L, 10, 0);

		assertThat(option.getListPrice()).isEqualTo(30_000L);
		assertThat(option.discountRate()).isZero();
	}

	@Test
	void 판매가가_정가보다_크면_생성할_수_없다() {
		assertThatThrownBy(() -> ProductOption.create(null, "3kg", 30_000L, 35_000L, 10, 0))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.PRICE_EXCEEDS_LIST_PRICE);
	}

	@Test
	void 수정할_때도_판매가가_정가보다_크면_거부한다() {
		ProductOption option = ProductOption.create(null, "3kg", 40_000L, 30_000L, 10, 0);

		assertThatThrownBy(() -> option.update("3kg", 30_000L, 35_000L, 0))
			.isInstanceOf(RestApiException.class)
			.extracting("errorCode").isEqualTo(ProductErrorCode.PRICE_EXCEEDS_LIST_PRICE);
	}

	@Test
	void 할인율은_소수점을_버린다() {
		ProductOption twenty = ProductOption.create(null, "a", 40_000L, 32_000L, 10, 0);
		ProductOption third = ProductOption.create(null, "b", 30_000L, 20_000L, 10, 1);

		assertThat(twenty.discountRate()).isEqualTo(20);
		assertThat(third.discountRate()).isEqualTo(33);
	}

	@Test
	void 새_옵션의_판매_가능_수량은_총재고와_같다() {
		ProductOption option = ProductOption.create(null, "3kg", null, 30_000L, 10, 0);

		assertThat(option.getAllocatedQuantity()).isZero();
		assertThat(option.availableStock()).isEqualTo(10);
	}

	@Test
	void 판매_중인_상품의_재고가_남은_옵션은_구매할_수_있다() {
		// given
		ProductOption sut = optionOfOnSaleProduct(10);

		// when & then
		assertThat(sut.isOnSale()).isTrue();
		assertThat(sut.isPurchasable()).isTrue();
	}

	@Test
	void 재고가_0이면_판매_조건은_만족해도_구매할_수_없다() {
		// given
		ProductOption sut = optionOfOnSaleProduct(0);

		// when & then
		assertThat(sut.isOnSale()).isTrue();
		assertThat(sut.isPurchasable()).isFalse();
	}

	@Test
	void 삭제한_옵션은_구매할_수_없다() {
		// given
		ProductOption sut = optionOfOnSaleProduct(10);

		// when
		sut.delete(LocalDateTime.of(2026, 10, 14, 12, 0));

		// then
		assertThat(sut.isOnSale()).isFalse();
		assertThat(sut.isPurchasable()).isFalse();
	}

	@Test
	void 상품이_판매_중이_아니면_옵션도_구매할_수_없다() {
		// given
		ProductOption sut = optionOfOnSaleProduct(10);

		// when
		sut.getProduct().changeStatus(ProductStatus.PAUSED);

		// then
		assertThat(sut.isOnSale()).isFalse();
		assertThat(sut.isPurchasable()).isFalse();
	}

	private ProductOption optionOfOnSaleProduct(int totalStock) {
		Product product = Product.create(1L, "꿀고구마", "<p>설명</p>", ProductCategory.ROOT_CROP, ProductRegion.JEONNAM,
			List.of(ProductOptionSpec.newOption("3kg", null, 30_000L, totalStock)),
			List.of("https://bucket/product/a.jpg"));
		product.changeStatus(ProductStatus.ON_SALE);
		product.changeVisibility(ProductVisibility.VISIBLE);
		return product.getOptions().getFirst();
	}

}
