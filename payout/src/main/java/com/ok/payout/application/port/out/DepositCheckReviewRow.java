package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutDepositMismatchReason;

import java.time.LocalDateTime;

// 관리자 대사 확인 목록(#621)의 한 줄 = 관리자 확인 필요(NEEDS_REVIEW)인 대사 줄(payout_deposit_check) 1줄
// 판매자·금액은 대사 줄에 복사해 둔 값을 그대로 읽음 (정산 표를 조인하지 않음, PayoutDepositCheck 주석)
public record DepositCheckReviewRow(
        Long payoutId,
        Long payeeId,                 // 정산이 기대한 판매자 회원 ID
        Long amount,                  // 정산 금액
        Long walletPayeeId,           // 지갑이 "있음"으로 답한 판매자. "있음" 답을 받지 못했으면 null
        Long walletAmount,            // 지갑이 "있음"으로 답한 금액. "있음" 답을 받지 못했으면 null
        PayoutDepositMismatchReason mismatchReason,
        Integer attemptCount,         // Querydsl이 생성자를 타입으로 찾아서 엔티티 필드(int)와 맞는 감싼 타입으로 둠 (BlockedOrderRow와 같음)
        Integer notFoundCount,        // 지갑이 "없음"으로 답한 횟수. attemptCount와 같은 이유로 감싼 타입
        LocalDateTime lastCheckedAt   // 지갑에 마지막으로 물으려 한 시각 (답 받은 시각이 아님, 보내기에 실패한 시도의 시각일 수 있음)
) {
}
