package com.ok.payout.service.dto;

import java.time.LocalDateTime;

// 지갑 차감 정보. 지갑 차감 기록 저장(PayoutWalletDeductionService)의 입력
// 서비스는 지갑 모듈과의 계약 타입(common.event.WalletPaymentCompletedEvent)을 직접 받지 않고,
// 리스너(event 패키지)가 이벤트를 이 커맨드로 바꿔 넘긴다 (structure.md 이벤트 수신 규칙)
// 필드는 WalletPaymentCompletedEvent와 같다. 이벤트 필드가 바뀌면 리스너의 변환과 함께 바꾼다
public record WalletPaymentCompletedCommand(
        // 결제한 주문 ID (지갑 차감 기록의 중복 처리 기준)
        Long orderId,
        // 구매자 회원 ID
        Long buyerId,
        // 차감한 금액 (원)
        Long amount,
        // 지갑이 차감한 시각
        LocalDateTime paidAt
) {
}
