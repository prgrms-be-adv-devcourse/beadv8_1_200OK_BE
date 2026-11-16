package com.ok.payout.application.port.out;

import java.time.LocalDateTime;

/**
 * 발송 대기 중인 outbox 한 줄과 보낼 메시지를 만드는 데 필요한 정산 값.
 */
public record PayoutOutboxRow(Long outboxId, Long payoutId, Long payeeId, Long amount, LocalDateTime payoutCreatedAt) {
}
