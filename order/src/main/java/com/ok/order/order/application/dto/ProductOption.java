package com.ok.order.order.application.dto;

/**
 * 주문이 상품 모듈에게서 받는 옵션 정보. 상품 모듈 응답 모양이 아니라 주문이 쓰는 모양이다.
 *
 * @param salePrice      지금 판매가 (원)
 * @param availableStock 지금 주문할 수 있는 재고
 * @param purchasable    판매 중이라 살 수 있는지
 */
public record ProductOption(
        Long productId,
        Long optionId,
        Long sellerId,
        String productName,
        String optionName,
        long salePrice,
        int availableStock,
        boolean purchasable
) {
}
