package com.ok.order.order.domain;

// outbox 줄의 발송 상태
public enum OrderOutboxStatus {
    PENDING,  // 보낼 차례: 아직 보내지 못함
    SENT      // 보냄: 브로커가 저장을 확인함
}
