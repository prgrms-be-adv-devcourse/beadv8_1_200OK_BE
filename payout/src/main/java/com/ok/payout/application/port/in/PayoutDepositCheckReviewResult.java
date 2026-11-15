package com.ok.payout.application.port.in;

import java.time.LocalDateTime;

// 관리자 대사 확인 목록 1줄 = 관리자 확인 필요(NEEDS_REVIEW)인 정산 1건 (#621)
public record PayoutDepositCheckReviewResult(
        Long payoutId,
        // 정산이 기대한 판매자 회원 ID와 정산 금액
        Long payeeId,
        Long amount,
        // 지갑이 "있음"으로 답한 판매자·금액. 입금 없음·지갑 답 없음이면 null
        Long walletPayeeId,
        Long walletAmount,
        // 이유 이름 (NOT_FOUND, NO_RESPONSE, PAYEE_MISMATCH, AMOUNT_MISMATCH). 입구가 domain enum을 모르게 문자열로 넘김
        String reason,
        // 지갑에 물어본 횟수
        int attemptCount,
        // 지갑에 마지막으로 물은 시각 (답 받은 시각이 아님, PayoutDepositCheck.recordAsked)
        LocalDateTime lastAskedAt
) {
}
