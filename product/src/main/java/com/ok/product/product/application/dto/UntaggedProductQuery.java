package com.ok.product.product.application.dto;

import java.time.LocalDate;

/**
 * 태그 0개 상품 조회 조건. 판정 시각 기준이고, 기간은 날짜 단위로 시작일과 종료일을 모두 포함한다.
 *
 * @param limit 돌려줄 항목 수 상한
 */
public record UntaggedProductQuery(
	LocalDate from,
	LocalDate to,
	int limit
) {
}
