package com.ok.common.event.payload;

import java.util.Objects;

/**
 * 결제된 주문 상품 항목을 담는다. {@link PaidSellerOrder}의 일부이며 상품명, 옵션명, 단가는 결제 시점 값이다.
 *
 * @param orderItemId 주문 항목 ID
 * @param productName 상품명
 * @param optionName  옵션명. 옵션이 없는 상품이면 {@code null}
 * @param quantity    주문 수량. 1 이상
 * @param unitPrice   상품 1개 가격(원). 0 이상
 */
public record PaidOrderItem(
        Long orderItemId,
        String productName,
        String optionName,
        Integer quantity,
        Long unitPrice
) {

    public PaidOrderItem {
        Objects.requireNonNull(orderItemId, "orderItemId는 필수입니다.");
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("productName은 필수입니다.");
        }
        Objects.requireNonNull(quantity, "quantity는 필수입니다.");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(unitPrice, "unitPrice는 필수입니다.");
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice는 0 이상이어야 합니다.");
        }
    }
}
