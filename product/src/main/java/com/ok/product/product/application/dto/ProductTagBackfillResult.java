package com.ok.product.product.application.dto;

/**
 * 태그 판정 백필 결과.
 *
 * @param createdCount  판정 행이 없어 새로 대기로 넣은 상품 수
 * @param requeuedCount 태그 목록 버전이 바뀌었거나 최종 실패해 다시 대기로 돌린 상품 수
 */
public record ProductTagBackfillResult(int createdCount, int requeuedCount) {
}
