package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutCandidateResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

// 관리자가 보는 정산후보 1건. 판매자용은 PayoutSellerCandidateResponse
@Schema(description = "관리자 정산후보 1건")
public record PayoutCandidateResponse(

        @Schema(description = "정산후보 ID", example = "55")
        Long candidateId,

        @Schema(description = "항목 종류", example = "SALE_AMOUNT", allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        String eventType,

        @Schema(description = "주문 ID", example = "300")
        Long orderId,

        @Schema(description = "주문 항목 ID. 배송비 후보는 null (배송비는 상품이 아니라 판매자 묶음 배송 1건의 금액)",
                example = "900", nullable = true)
        Long orderItemId,

        @Schema(description = "판매자 회원 ID", example = "7")
        Long sellerId,

        @Schema(description = "금액 (원). 조정 건이면 음수일 수 있음", example = "9700")
        Long amount,

        @Schema(description = "정산후보 생성 시각. 평소 건은 구매확정 직후, 조정 건은 조정한 시각", example = "2026-10-14T15:20:00")
        LocalDateTime candidateCreatedAt,

        @Schema(description = "정산 완료 여부", example = "true")
        boolean settled,

        @Schema(description = "정산된 후보면 그 정산 ID, 정산 전이면 null. 수수료 후보면 시스템 몫 정산 ID", example = "12", nullable = true)
        Long payoutId,

        // nullable = true를 두지 않음: 객체 칸에 붙이면 Swagger 문서에 "type": "null"로 적혀 코드 생성기가 늘 null인 칸으로 읽음 (#723). 없을 수 있다는 것은 설명에 적음
        @Schema(description = "관리자 조정 정보. 평소 건이면 null")
        PayoutAdjustmentResponse adjustment,

        // 문자열 칸이라 nullable = true를 붙여도 위 객체 칸 문제가 없음
        // allowableValues는 두지 않음: 문서(OpenAPI 3.1)에 "type": ["string", "null"]과 null 없는 enum이 함께 적혀,
        //   엄격한 검증기·코드 생성기가 null 응답을 enum 위반으로 읽음 (#791에서 /v3/api-docs로 확인). 값 목록은 설명에 적음
        @Schema(description = "조정이 금액 검사에 들어가는지. orderId로 조회한 조정 건에만 나오고, 평소 건과 기간만 준 조회에서는 null. "
                + "CONFIRMED 확인됨: 금액·수수료 불일치 실패 뒤·해결 전에 넣고 해결한 조정이라 금액 검사에 들어감. "
                + "AWAITING_RESOLUTION 해결 대기: 해결 안 된 금액·수수료 불일치 실패 뒤에 넣은 조정이라, 막힌 주문을 해결하면 확인됨이 됨. "
                + "UNCHECKED 검사 밖: 그 밖의 조정(실패 전에 미리 넣음, 해결 뒤에 넣음, 막힘과 상관없는 조정)이라 금액 검사 없이 지급에만 들어감",
                example = "CONFIRMED", nullable = true)
        String adjustmentCheckStatus
) {

    public static PayoutCandidateResponse from(PayoutCandidateResult result) {
        PayoutAdjustmentResponse adjustment = result.adjustment() == null
                ? null
                : PayoutAdjustmentResponse.from(result.adjustment());
        return new PayoutCandidateResponse(result.candidateId(), result.eventType(), result.orderId(),
                result.orderItemId(), result.sellerId(), result.amount(), result.candidateCreatedAt(),
                result.settled(), result.payoutId(), adjustment, result.adjustmentCheckStatus());
    }
}
