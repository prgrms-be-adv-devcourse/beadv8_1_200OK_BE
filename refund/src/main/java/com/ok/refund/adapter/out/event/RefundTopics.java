package com.ok.refund.adapter.out.event;

/**
 * 환불이 보내는 Kafka 토픽 이름을 한곳에 둔다.
 * 토픽 이름은 받는 모듈(지갑)과의 약속이라 발행 어댑터와 토픽 선언 설정이 같은 값을 쓰도록 상수로 둔다.
 * 이벤트 어댑터 패키지에 두는 이유: 토픽 이름은 이벤트 어댑터 안에만 둔다 (refund/CLAUDE.md 5절, {@code ArchitectureTest}의 메시징 규칙).
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class RefundTopics {

    /**
     * 환불 → 지갑: 판매자가 승인해 환불이 처리 중이 됨. 지갑이 받아 구매자에게 예치금을 돌려준다(#413 메시지 약속).
     * 키는 orderId, 값은 {@link RefundApprovedMessage}
     */
    public static final String REFUND_APPROVED = "refund.refund-approved";

    private RefundTopics() {
    }
}
