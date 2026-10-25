package com.ok.common.event;

import java.time.LocalDateTime;

public record SellerPayoutCreatedEvent(
        Long payoutId,              // 정산 ID (지갑 측 중복 반영 방지 기준)
        Long payeeId,               // 받는 사람 회원 ID (판매자)
        Long amount,                // 정산 금액 (정산 항목 합계)
        LocalDateTime createdAt     // 정산 생성 시각
) implements DomainEvent {

}
