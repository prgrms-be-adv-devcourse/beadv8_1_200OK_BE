package com.ok.common.event;

import java.time.LocalDateTime;

public record SellerPayoutCreatedEvent(
        Long payoutId,       // 정산 ID
        Long payeeId,        // 받는 사람 회원 ID (판매자)
        Long amount,          // 정산 금액 (정산 항목 합계)
        LocalDateTime createdAt //정산생성시간
) {

}
