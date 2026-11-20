package com.ok.product.product.adapter.in.web.dto;

import java.time.LocalDateTime;

import com.ok.product.product.application.dto.ZeroResultKeywordResult;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "결과가 0건이었던 검색어 한 항목")
public record ZeroResultKeywordResponse(

	@Schema(description = "공백을 없앤 검색어. 띄어쓰기만 다른 검색어를 이 값으로 묶는다", example = "샤인머스켓")
	String normalizedKeyword,

	@Schema(description = "기간 안에 결과가 0건이었던 횟수", example = "12")
	long zeroResultCount,

	@Schema(description = "기간 안의 마지막 0건 검색 시각 (한국 시간)", example = "2026-11-18T14:05:31")
	LocalDateTime lastSearchedAt,

	@Schema(description = "이 묶음에서 가장 최근에 들어온 검색어 원문", example = "샤인 머스켓")
	String sampleKeyword
) {

	public static ZeroResultKeywordResponse from(ZeroResultKeywordResult result) {
		return new ZeroResultKeywordResponse(result.normalizedKeyword(), result.zeroResultCount(),
			result.lastSearchedAt(), result.sampleKeyword());
	}
}
