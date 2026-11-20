package com.ok.product.product.application.dto;

import java.time.LocalDate;

/**
 * 0건 검색어 조회 조건. 기간은 날짜 단위로 시작일과 종료일을 모두 포함한다.
 *
 * @param limit 돌려줄 항목 수 상한
 */
public record ZeroResultKeywordQuery(
	LocalDate from,
	LocalDate to,
	int limit
) {
}
