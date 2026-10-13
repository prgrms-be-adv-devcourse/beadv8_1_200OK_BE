package com.ok.order.cart.domain;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.exception.CartErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartItemTest {

    @Test
    void 수량을_1개_이상으로_변경할_수_있다() {
        // Given
        CartItem item = CartItem.create(Cart.create(1L), 100L, 1);

        // When
        item.changeQuantity(3);

        // Then
        assertThat(item.getQuantity()).isEqualTo(3);
    }

    @Test
    void 수량을_0개_이하로_변경할_수_없다() {
        // Given
        CartItem item = CartItem.create(Cart.create(1L), 100L, 1);

        // When & Then
        assertThatThrownBy(() -> item.changeQuantity(0))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(CartErrorCode.INVALID_QUANTITY);
        assertThat(item.getQuantity()).isEqualTo(1);   // 실패하면 원래 수량 유지
    }

    @Test
    void 상품을_선택_해제하고_다시_선택할_수_있다() {
        // Given
        CartItem item = CartItem.create(Cart.create(1L), 100L, 1);   // 담으면 기본 선택

        // When & Then
        item.changeSelected(false);
        assertThat(item.isSelected()).isFalse();

        item.changeSelected(true);
        assertThat(item.isSelected()).isTrue();
    }
}