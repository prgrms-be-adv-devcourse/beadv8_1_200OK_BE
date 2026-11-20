package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutAdjustmentCreateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "정산 수동 조정 결과: 방금 저장한 조정 정산후보 (#291)")
public record PayoutAdjustmentCreateResponse(

        @Schema(description = "생성된 조정 정산후보 ID", example = "1234")
        Long candidateId,

        @Schema(description = "서버가 붙인 조정 번호. 같은 주문 항목·종류에서 1, 2, 3… 순서", example = "1")
        Long adjustmentId,

        @Schema(description = "주문 ID (원래 정산후보에서 가져온 값)", example = "100")
        Long orderId,

        @Schema(description = "주문 항목 ID", example = "900")
        Long orderItemId,

        @Schema(description = "판매자 회원 ID (원래 정산후보에서 가져온 값)", example = "7")
        Long sellerId,

        @Schema(description = "정산 항목 종류", example = "SALE_AMOUNT")
        String eventType,

        @Schema(description = "조정 금액 (원). 음수면 차감", example = "-2000")
        Long amount,

        @Schema(description = "조정 사유", example = "금액 오입력 정정")
        String reason,

        @Schema(description = "조정한 관리자 회원 ID", example = "1")
        Long adminId,

        @Schema(description = "조정 생성 시각", example = "2026-11-16T10:00:00")
        LocalDateTime createdAt,

        // 이 칸은 늘 값이 있어(null 없음) 정산후보 조회 칸과 달리 allowableValues를 둠
        @Schema(description = """
                방금 넣은 조정이 금액 검사에 들어가는지. 조정은 막지 않고 저장하며, 이 값으로 알려 주기만 합니다.
                AWAITING_RESOLUTION: 해결 안 된 금액·수수료 불일치 실패 뒤에 넣음. 막힌 주문을 해결하면 금액 검사에 들어갑니다.
                UNCHECKED: 금액 검사 없이 지급에만 들어갑니다. 그 주문에 해결 안 된 금액·수수료 불일치 실패가 없을 때입니다(실패 전에 미리 넣음, 해결한 뒤에 넣음, 실패가 없거나 결제 기록 없음 같은 다른 사유로만 막힌 주문). 의도한 조정이 아니면 바로 이 조정의 반대 금액 조정(사유를 다르게)으로 상쇄하세요. 그 조정도 검사 밖이라 지급에서만 지워집니다. 확인됨(CONFIRMED) 조정은 상쇄하지 마세요(돈이 더 나갈 수 있음).
                CONFIRMED: 정산후보 조회 칸과 같은 판정이라 목록에 있지만, 넣는 순간에는 해결 시각이 조정보다 늦어야 해서 서버 시계 차이 같은 예외가 아니면 나오지 않습니다.
                넣은 순간의 값입니다. AWAITING_RESOLUTION 조정은 해결하면 CONFIRMED가 되니, 그 뒤 상태는 정산후보 조회(orderId)의 adjustmentCheckStatus로 보세요(같은 판정).""",
                example = "UNCHECKED", allowableValues = {"CONFIRMED", "AWAITING_RESOLUTION", "UNCHECKED"})
        String adjustmentCheckStatus
) {

    public static PayoutAdjustmentCreateResponse from(PayoutAdjustmentCreateResult result) {
        return new PayoutAdjustmentCreateResponse(result.candidateId(), result.adjustmentId(), result.orderId(),
                result.orderItemId(), result.sellerId(), result.eventType(), result.amount(), result.reason(),
                result.adminId(), result.createdAt(), result.adjustmentCheckStatus());
    }
}
