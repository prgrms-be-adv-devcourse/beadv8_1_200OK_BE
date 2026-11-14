package com.ok.payout.application.port.in;

import java.util.List;

// 초과 청구 근거 8개 (#570). 실패 상세 글(#391)과 같은 값을 칸으로 나눈 것
public record PayoutOverchargeEvidenceResult(
        // 결제(지갑 차감) 금액
        long deductedAmount,
        // 정산후보 합계 (조정 제외, 아직 대기 중인 같은 주문 후보 포함)
        long candidateTotal,
        // 후보 합계 - 결제 금액. "지금" 값은 초과가 풀렸으면 0이나 음수
        long overflowAmount,
        // 넘게 만든 주문상품 ID, 배송비면 "배송비", 못 찾으면 "판별 불가"
        String firstOverflowItem,
        // 이미 지급한 금액
        long settledAmount,
        // 이미 지급한 주문상품 (배송비는 "배송비")
        List<String> settledItems,
        // 혼자 결제 금액을 넘는 주문상품 (있으면 그 주문상품 금액이 틀린 게 확실)
        List<String> aloneOverItems,
        // 그 주문 정산후보의 판매자 수
        int sellerCount,
        // 지갑 금액 충돌로 받은 금액들 (없으면 빈 목록)
        List<Long> conflictAmounts
) {
}
