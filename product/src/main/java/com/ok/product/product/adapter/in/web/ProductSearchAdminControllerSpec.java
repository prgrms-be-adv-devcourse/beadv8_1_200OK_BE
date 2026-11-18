package com.ok.product.product.adapter.in.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import com.ok.product.product.adapter.in.web.dto.ZeroResultKeywordResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Tag(name = "Product Search (Admin)", description = "관리자 검색 품질 점검 API")
interface ProductSearchAdminControllerSpec {

	@Operation(summary = "0건 검색어 조회", description = """
		기간 안에 결과가 0건이었던 구매자 키워드 검색을 공백을 없앤 검색어로 묶어 횟수가 많은 순으로 돌려줍니다.
		횟수가 같으면 마지막 검색 시각이 늦은 순, 그다음 검색어 순입니다. 결과가 1건 이상이었던 검색은 세지 않습니다.
		기간은 한국 시간 날짜 단위이고 시작일과 종료일을 모두 포함합니다.
		검색어 로그는 보존 기간(기본 90일)이 지나면 지워지므로, 시작일은 오늘에서 보존 기간을 뺀 날 이후여야 합니다.
		응답에 회원을 알 수 있는 값은 없습니다.
		""")
	@ApiResponses({
		@ApiResponse(responseCode = "200", description = "성공. 0건 검색이 없으면 빈 목록"),
		@ApiResponse(
			responseCode = "400", description = """
				시작일이 종료일보다 늦음(product/invalid-search-log-period), 시작일이 보존 기간 밖(product/search-log-period-beyond-retention),
				from·to가 없거나 yyyy-MM-dd 형식이 아님, limit이 1~100 밖""",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "401", description = "로그인 필요",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		),
		@ApiResponse(
			responseCode = "403", description = "ADMIN이 아님",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))
		)
	})
	ResponseEntity<List<ZeroResultKeywordResponse>> getZeroResultKeywords(
		@Parameter(description = "시작일 (yyyy-MM-dd, 포함)", example = "2026-11-01") LocalDate from,
		@Parameter(description = "종료일 (yyyy-MM-dd, 포함)", example = "2026-11-18") LocalDate to,
		@Parameter(description = "돌려줄 항목 수 (1~100)", example = "20") @Min(1) @Max(100) int limit
	);
}
