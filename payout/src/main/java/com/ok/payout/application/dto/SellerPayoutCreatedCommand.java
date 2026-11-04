package com.ok.payout.application.dto;

import java.time.LocalDateTime;

// 판매자 몫 정산이 확정되었음을 발행 인터페이스(PayoutEventPublisher)에 넘기는 값
// 서비스는 지갑과 약속한 메시지 DTO(event.dto.SellerPayoutCreatedMessage)를 직접 만들지 않고 이 값만 넘긴다 (structure.md 이벤트 발행 규칙)
// Payout 엔티티를 넘기지 않는 이유: 발행 구현체(event 패키지)가 domain을 쓰면 ArchitectureTest에 걸린다
// 필드는 SellerPayoutCreatedMessage와 같다. 메시지 필드가 바뀌면 함께 바꾼다
public record SellerPayoutCreatedCommand(
        // 정산 ID (지갑 측 중복 반영 방지 기준)
        Long payoutId,
        // 받는 사람 회원 ID (판매자)
        Long payeeId,
        // 정산 금액 (원). 0원 이하는 서비스에서 걸러서 넘기지 않는다
        Long amount,
        // 정산 확정 시각
        LocalDateTime createdAt
) {
}
