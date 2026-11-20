package com.ok.order.order.adapter.out.product.dto;

import com.ok.order.order.application.dto.ProductOption;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductOptionInfoResponseTest {

    @Test
    void 상품_모듈_응답을_주문의_상품_옵션으로_옮긴다() {
        // given
        ProductOptionInfoResponse response = new ProductOptionInfoResponse(
                10L, 11L, 100L, "사과", "1kg", 10_000L, 5, true);

        // when
        ProductOption option = response.toProductOption();

        // then
        assertThat(option).isEqualTo(new ProductOption(10L, 11L, 100L, "사과", "1kg", 10_000L, 5, true));
    }
}
