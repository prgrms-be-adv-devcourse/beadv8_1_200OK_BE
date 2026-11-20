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
        // 지갑이 "없음"으로 답한 횟수. 입금 없음(NOT_FOUND)은 한 번만 "없음"이어도 되므로,
        //   시도 횟수와 비교해 나머지가 답이 없었는지(지갑 수신 장애가 섞였는지) 관리자가 알 수 있게 함
        int notFoundCount,
        // 지갑에 마지막으로 물으려 한 시각 (답 받은 시각이 아님, 보내기 실패로 되돌린 시도의 시각일 수 있음, PayoutDepositCheck.undoAsked)
        LocalDateTime lastAskedAt
) {
}
