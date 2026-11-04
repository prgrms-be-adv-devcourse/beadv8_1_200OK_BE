package com.ok.payout.application.dto;

import com.ok.payout.domain.PayoutFailureReason;

// 이번 스냅샷 실패 기록 한 줄
public record FailureRow(Long candidateId, Long orderId, PayoutFailureReason reason) {
}
