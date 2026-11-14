package com.ok.payout.application.port.in;

public record PayoutOrderResolveCommand(
        // 해결할 주문 ID
        Long orderId,
        // 해결한 관리자 회원 ID (로그인 토큰에서 받음)
        Long adminId,
        // 해결 메모 (앞뒤 공백 제거됨, 255자 이하)
        String memo
) {
}
