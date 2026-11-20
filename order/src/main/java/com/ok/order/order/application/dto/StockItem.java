package com.ok.order.order.application.dto;

/**
 * 재고를 잡을 옵션과 개수.
 */
public record StockItem(Long optionId, int quantity) {
}
