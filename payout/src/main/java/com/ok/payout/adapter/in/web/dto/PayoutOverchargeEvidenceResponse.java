package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutOverchargeEvidenceResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "초과 청구 근거 8개. 실패 상세 글(원인의 detail)과 같은 값을 칸으로 나눈 것 "
        + "(\"이미 지급\"은 금액과 주문상품 두 필드라 필드는 9개)")
public record PayoutOverchargeEvidenceResponse(

        @Schema(description = "결제(지갑 차감) 금액 (원)", example = "30000")
        long deductedAmount,

        @Schema(description = "정산후보 합계 (원). 조정은 빼고, 아직 대기 중인 같은 주문 후보는 포함", example = "35000")
        long candidateTotal,

        @Schema(description = "넘친 금액 = 후보 합계 - 결제 금액 (원). 지금 값은 초과가 풀렸으면 0이나 음수", example = "5000")
        long overflowAmount,

        @Schema(description = "넘게 만든 주문상품: 생성 순서로 더할 때 처음 결제 금액을 넘긴 주문상품 ID. 배송비면 \"배송비\", "
                + "못 찾으면 \"판별 불가\". 이 상품이 틀렸다는 뜻은 아님(언제부터 넘었는지 단서)", example = "102")
        String firstOverflowItem,

        @Schema(description = "이미 지급한 금액 (원). 막아도 돌아오지 않는 돈", example = "10000")
        long settledAmount,

        @Schema(description = "이미 지급한 주문상품 ID (배송비는 \"배송비\")", example = "[\"101\"]")
        List<String> settledItems,

        @Schema(description = "혼자 결제 금액을 넘는 주문상품 ID. 있으면 그 주문상품 금액이 틀린 게 확실", example = "[\"102\"]")
        List<String> aloneOverItems,

        @Schema(description = "그 주문 정산후보의 판매자 수. 여럿이면 어느 판매자 금액이 틀렸는지 따로 봐야 함", example = "1")
        int sellerCount,

        @Schema(description = "지갑 금액 충돌로 받은 금액들 (원). 있으면 결제 금액 자체가 틀렸을 수 있음", example = "[]")
        List<Long> conflictAmounts
) {

    // 근거가 없으면(초과 청구가 원인이 아니거나 계산할 수 없음) null
    public static PayoutOverchargeEvidenceResponse from(PayoutOverchargeEvidenceResult result) {
        if (result == null) {
            return null;
        }
        return new PayoutOverchargeEvidenceResponse(result.deductedAmount(), result.candidateTotal(),
                result.overflowAmount(), result.firstOverflowItem(), result.settledAmount(), result.settledItems(),
                result.aloneOverItems(), result.sellerCount(), result.conflictAmounts());
    }
}
