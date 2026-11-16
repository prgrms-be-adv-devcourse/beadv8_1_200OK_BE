package com.ok.order.order.adapter.in.web;

import com.ok.order.order.adapter.in.web.dto.OrderItemForRefundResponse;
import com.ok.order.order.adapter.in.web.dto.RecordOrderItemRefundRequest;
import com.ok.order.order.adapter.in.web.dto.ReviewCandidateRequest;
import com.ok.order.order.adapter.in.web.dto.ReviewCandidateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

    @Operation(summary = "환불용 주문 상품 조회", description = """
            환불 모듈이 환불 요청과 승인 때 주문 상품 정보, 배송 상태, 판매자 묶음의 배송비 값을 확인하는 데 사용합니다.
            읽기만 하고 주문 상품 상태는 바꾸지 않습니다. 구매자는 확인하지 않으므로 환불이 응답의 buyerId로 확인합니다.
            sellerGroup.items에는 조회한 주문 상품을 포함해 같은 판매자 묶음의 모든 주문 상품이 들어갑니다.
            """)
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "주문이 없음(order/order-not-found), 또는 그 주문에 해당 주문 상품이 없음(order/order-item-not-found)")
    ResponseEntity<OrderItemForRefundResponse> getOrderItemForRefund(
            @Parameter(description = "주문 ID", example = "10") Long orderId,
            @Parameter(description = "주문 상품 ID", example = "101") Long orderItemId
    );

    @Operation(summary = "환불 취소 반영", description = """
            환불 모듈이 판매자 승인 직후(처리 중이 된 환불) 주문 상품의 취소 수량과 환불에서 뺀 배송비를 알립니다.
            주문 상품 상태는 바꾸지 않고 기록만 남기며, 구매확정 때 상품 금액에서 환불 수량을 빼고 뺀 배송비는 판매자 몫 배송비에 더합니다.
            같은 refundId로 같은 내용을 다시 보내면 한 번만 반영하고 204를 돌려줍니다.
            """)
    @ApiResponse(responseCode = "204", description = "반영함, 또는 같은 refundId·같은 내용으로 이미 반영됨")
    @ApiResponse(responseCode = "404", description = "주문 상품이 없음(order/order-item-not-found)")
    @ApiResponse(responseCode = "409", description = """
            출고 전(배송 준비) 상품이 아님(order/refund-not-allowed-status),
            환불 수량 합계가 주문 수량을 넘음(order/refund-quantity-exceeded),
            같은 refundId인데 수량이나 뺀 배송비가 다름(order/refund-id-conflict)
            """)
    @ApiResponse(responseCode = "422", description = "요청 값 검증 실패 (환불 ID·수량 1 이상, 뺀 배송비 0 이상)")
    ResponseEntity<Void> recordRefund(
            @Parameter(description = "주문 상품 ID", example = "101") Long orderItemId,
            RecordOrderItemRefundRequest request
    );
}