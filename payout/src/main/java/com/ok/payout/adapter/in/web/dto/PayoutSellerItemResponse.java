package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutItemResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 판매자가 보는 정산 항목 1건. 관리자용(PayoutItemResponse)과 같고, 조정 정보만 판매자용(사유만)으로 바꾼 응답
@Schema(description = "판매자 정산 항목 1건 (정산후보 정보 포함)")
public record PayoutSellerItemResponse(

        @Schema(description = "정산 항목 ID", example = "101")
        Long payoutItemId,

        @Schema(description = "이 항목이 된 정산후보 ID", example = "55")
        Long candidateId,

        @Schema(description = "항목 종류", example = "SALE_AMOUNT", allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "주문 ID", example = "300")
        Long orderId,

        @Schema(description = "주문 항목 ID. 배송비 항목은 null (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액)",
                example = "900", nullable = true)
        Long orderItemId,

        @Schema(description = "판매자 회원 ID. 판매자 본인", example = "7")
        Long sellerId,

        @Schema(description = "항목 금액 (원). 판매 대금(SALE_AMOUNT)은 이미 판매 수수료를 뺀 값(예: 판매 10,000원이면 9,700원, 수수료 300원은 플랫폼 몫으로 따로 정산). "
                + "조정 건이면 음수일 수 있음", example = "9700")
        Long amount,

        @Schema(description = "정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각", example = "2026-10-14T15:20:00")
        LocalDateTime candidateCreatedAt,

        // nullable = true를 두지 않음: 객체 칸에 붙이면 Swagger 문서에 "type": "null"로 적혀 코드 생성기가 늘 null인 칸으로 읽음 (#723). 없을 수 있다는 것은 설명에 적음
        @Schema(description = "관리자가 금액을 직접 고친 조정 건이면 조정 사유, 구매확정으로 생긴 평소 건이면 null")
        PayoutSellerAdjustmentResponse adjustment
) {

    public static PayoutSellerItemResponse from(PayoutItemResult result) {
        PayoutSellerAdjustmentResponse adjustment = result.adjustment() == null
                ? null
                : PayoutSellerAdjustmentResponse.from(result.adjustment());
        return new PayoutSellerItemResponse(result.payoutItemId(), result.candidateId(), result.eventType(),
                result.orderId(), result.orderItemId(), result.sellerId(), result.amount(),
                result.candidateCreatedAt(), adjustment);
    }
}
