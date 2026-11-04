package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutFailureReason;

// 이번 스냅샷 실패 기록 한 줄. verifyStep 결과 검증(#290)에서 스냅샷·draft와 후보 단위로 비교하려고 가져옴
public record FailureRow(Long candidateId, Long orderId, PayoutFailureReason reason) {
}
