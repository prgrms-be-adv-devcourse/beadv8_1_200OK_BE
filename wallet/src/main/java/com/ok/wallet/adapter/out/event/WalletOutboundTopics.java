package com.ok.wallet.adapter.out.event;

/**
 * 지갑이 Kafka로 보내는 토픽 이름을 한곳에 둔다. 토픽 이름은 받는 모듈과의 약속이라 상수로 고정한다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class WalletOutboundTopics {
    /** 지갑 → 환불: 예치금 반환을 마침. 키는 orderId, 값은 {@link com.ok.wallet.adapter.out.event.dto.WalletRefundCompletedMessage} */
    public static final String WALLET_REFUND_COMPLETED = "wallet.wallet-refund-completed";
    /** 지갑 → 환불: 예치금 반환을 거절함. 키는 orderId, 값은 {@link com.ok.wallet.adapter.out.event.dto.WalletRefundFailedMessage} */
    public static final String WALLET_REFUND_FAILED = "wallet.wallet-refund-failed";

    private WalletOutboundTopics() {
    }
}
