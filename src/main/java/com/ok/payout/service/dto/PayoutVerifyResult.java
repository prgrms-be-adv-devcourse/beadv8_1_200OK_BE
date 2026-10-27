package com.ok.payout.service.dto;

// verifyStep 결과 (#290). tasklet이 배치 실행 기록에 수를 남기는 데 씀
// 전부 걸려 정산할 것이 0건인 경우는 verify가 결과를 돌려주지 않고 예외로 멈춤 (DRAFT_ALL_ORDERS_MISMATCHED)
public record PayoutVerifyResult(
        // 결과 검증 실패 주문을 빼고 commitStep이 정산할 draft 항목 수 (판매자 몫 + 시스템 몫)
        int remainingItemCount,
        // 이번 스냅샷에서 결과 검증 실패로 빠진 주문 수 (같은 스냅샷을 다시 검증한 경우 이전에 빠진 주문 포함)
        int excludedOrderCount,
        // 이번 검증에서 새로 저장한 VERIFY_MISMATCH 실패 기록 수 (원인 후보 + 함께 보류된 후보)
        int recordedFailureCount
) {
}
