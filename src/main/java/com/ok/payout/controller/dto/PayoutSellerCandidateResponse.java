package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutCandidateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 판매자가 보는 정산후보 1건. 관리자용(PayoutCandidateResponse)과 달리
//   - 조정 정보는 사유만 (PayoutSellerAdjustmentResponse, #198과 같은 기준)
//   - 수수료 후보의 정산 ID는 넣지 않음: 수수료는 시스템 몫 정산에 들어가 그 ID가 시스템 몫 정산 ID라서
//     (판매자는 그 정산을 열 수 없고(403), 지금은 판매자가 시스템 몫 정산을 알 필요가 없음. 바뀌면 이 부분만 고침)
@Schema(description = "판매자 정산후보 1건")
public record PayoutSellerCandidateResponse(

        @Schema(description = "정산후보 ID", example = "55")
        Long candidateId,

        @Schema(description = "항목 종류", example = "SALE_AMOUNT", allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "주문 ID", example = "300")
        Long orderId,

        @Schema(description = "주문 항목 ID. 배송비 후보는 null (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액)",
                example = "900", nullable = true)
        Long orderItemId,

        @Schema(description = "판매자 회원 ID. 판매자 본인", example = "7")
        Long sellerId,

        @Schema(description = "금액 (원). 조정 건이면 음수일 수 있음", example = "9700")
        Long amount,

        @Schema(description = "정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각", example = "2026-10-14T15:20:00")
        LocalDateTime candidateCreatedAt,

        @Schema(description = "정산 완료 여부", example = "true")
        boolean settled,

        @Schema(description = "정산된 후보면 그 정산 ID, 정산 전이면 null. 수수료 후보는 정산이 끝나도 null", example = "12", nullable = true)
        Long payoutId,

        @Schema(description = "관리자가 금액을 직접 고친 조정 건이면 조정 사유, 구매확정으로 생긴 평소 건이면 null", nullable = true)
        PayoutSellerAdjustmentResponse adjustment
) {

    public static PayoutSellerCandidateResponse from(PayoutCandidateResult result) {
        PayoutSellerAdjustmentResponse adjustment = result.adjustment() == null
                ? null
                : PayoutSellerAdjustmentResponse.from(result.adjustment());
        Long payoutId = result.systemShare() ? null : result.payoutId();
        return new PayoutSellerCandidateResponse(result.candidateId(), result.eventType(), result.orderId(),
                result.orderItemId(), result.sellerId(), result.amount(), result.candidateCreatedAt(),
                result.settled(), payoutId, adjustment);
    }
}
