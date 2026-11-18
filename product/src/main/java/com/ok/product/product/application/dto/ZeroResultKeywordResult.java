package com.ok.product.product.application.dto;

import java.time.LocalDateTime;

/**
 * 정규화 검색어 하나로 묶은 0건 검색 집계 행.
 *
 * @param sampleKeyword 이 묶음에서 가장 최근에 들어온 검색어 원문
 */
public record ZeroResultKeywordResult(
	String normalizedKeyword,
	long zeroResultCount,
	LocalDateTime lastSearchedAt,
	String sampleKeyword
) {
}
