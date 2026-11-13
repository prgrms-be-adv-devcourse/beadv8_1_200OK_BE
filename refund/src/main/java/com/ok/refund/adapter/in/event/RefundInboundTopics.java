package com.ok.refund.adapter.in.event;

/**
 * 환불이 Kafka로 받는 토픽 이름과 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 보내는 모듈(지갑)과의 약속이라 리스너가 쓰는 값을 상수로 고정한다.
 * 이벤트 어댑터 패키지에 두는 이유: 토픽 이름은 이벤트 어댑터 안에만 둔다 (refund/CLAUDE.md 5절, {@code ArchitectureTest}의 메시징 규칙).
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class RefundInboundTopics {

    /** 지갑 → 환불: 예치금 반환을 마침. 키는 orderId */
    public static final String WALLET_REFUND_COMPLETED = "wallet.wallet-refund-completed";

    /** 지갑 → 환불: 예치금 반환에 실패함. 키는 orderId */
    public static final String WALLET_REFUND_FAILED = "wallet.wallet-refund-failed";

    // 그룹을 refund로 고정하는 이유: 같은 토픽을 다른 모듈이 받더라도 그룹이 다르면 메시지가 각자에게 모두 간다.
    // 환불 서버가 여러 대여도 같은 그룹이라 한 메시지는 한 대만 처리한다
    public static final String CONSUMER_GROUP = "refund";

    private RefundInboundTopics() {
    }
}
