package com.ok.order.order.controller;

import com.ok.order.order.controller.dto.ReviewCandidateRequest;
import com.ok.order.order.controller.dto.ReviewCandidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "Order (Internal)", description = "다른 모듈 연동용 주문 내부 API (외부 공개 안 함)")
interface OrderInternalControllerSpec {

    @Operation(summary = "리뷰 작성 가능 구매 건 조회", description = """
            상품 모듈이 리뷰 작성 화면과 리뷰 저장 직전 검증에 사용합니다.
            회원이 해당 상품을 산 주문 상품 중 구매확정된 것만 돌려줍니다.
            재구매하거나 옵션을 여러 개 샀으면 여러 건이고, 최근 주문이 먼저 옵니다. 없으면 빈 목록입니다.
            """)
    @ApiResponse(responseCode = "200", description = "조회 성공 (없으면 빈 목록)")
    @ApiResponse(responseCode = "422", description = "회원 ID 또는 상품 ID가 없음")
    ResponseEntity<List<ReviewCandidateResponse>> getReviewCandidates(@ParameterObject ReviewCandidateRequest request);
}