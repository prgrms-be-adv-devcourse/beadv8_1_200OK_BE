package com.ok.wallet.adapter.out.event;

/**
 * 지갑이 Kafka로 보내는 토픽 이름을 한곳에 둔다. 토픽 이름은 받는 모듈과의 약속이라 상수로 고정한다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>.v<버전>}이다. 버전은 메시지 모양을 호환되지 않게 바꿀 때 새 토픽으로 옮기려고 붙인다.
 * 버전 없는 옛 이름({@code *_OLD})은 받는 쪽(환불·정산)이 아직 그 이름으로 받아, 받는 쪽이 옮길 때까지 같은 메시지를 함께 보낸다.
 */
public final class WalletOutboundTopics {
    /** 지갑 → 환불: 예치금 반환을 마침. 키는 orderId, 값은 {@link com.ok.wallet.adapter.out.event.dto.WalletRefundCompletedMessage} */
    public static final String WALLET_REFUND_COMPLETED = "wallet.wallet-refund-completed.v1";
    /** {@link #WALLET_REFUND_COMPLETED}의 옛 이름. 환불이 v1로 옮기면 지운다 */
    public static final String WALLET_REFUND_COMPLETED_OLD = "wallet.wallet-refund-completed";
    /** 지갑 → 환불: 예치금 반환을 거절함. 키는 orderId, 값은 {@link com.ok.wallet.adapter.out.event.dto.WalletRefundFailedMessage} */
    public static final String WALLET_REFUND_FAILED = "wallet.wallet-refund-failed.v1";
    /** {@link #WALLET_REFUND_FAILED}의 옛 이름. 환불이 v1로 옮기면 지운다 */
    public static final String WALLET_REFUND_FAILED_OLD = "wallet.wallet-refund-failed";
    /** 지갑 → 정산: 정산 입금 확인의 답. 키는 checkId, 값은 {@link com.ok.wallet.adapter.out.event.dto.PayoutDepositCheckedMessage} */
    public static final String PAYOUT_DEPOSIT_CHECKED = "wallet.payout-deposit-checked.v1";
    /** {@link #PAYOUT_DEPOSIT_CHECKED}의 옛 이름. 정산이 v1로 옮기면 지운다 */
    public static final String PAYOUT_DEPOSIT_CHECKED_OLD = "wallet.payout-deposit-checked";

    private WalletOutboundTopics() {
    }
}
