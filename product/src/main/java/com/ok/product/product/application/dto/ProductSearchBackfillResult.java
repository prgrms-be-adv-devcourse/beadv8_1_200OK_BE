package com.ok.product.product.application.dto;

/**
 * 검색 행 백필 결과.
 *
 * @param processedCount 검색 행을 채운 상품 수
 * @param failedCount    실패한 묶음에 든 상품 수. 묶음 단위로 롤백되므로 묶음 전체를 센다
 */
public record ProductSearchBackfillResult(int processedCount, int failedCount) {
}
