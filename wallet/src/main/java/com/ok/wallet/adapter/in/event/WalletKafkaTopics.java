package com.ok.wallet.adapter.in.event;

/**
 * 지갑이 받는 Kafka 토픽 이름과 지갑 리스너의 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 보내는 모듈과의 약속이라 보내는 쪽과 같은 값이어야 받는다.
 * 이름 규칙(#648): 토픽은 {@code <보내는 모듈>.<이벤트>.v<버전>}, 그룹은 {@code <모듈>.<용도>}로 리스너마다 하나다.
 */
public final class WalletKafkaTopics {

    /** 정산 → 지갑: 판매자 정산금 확정. 키는 payoutId, 값은 {@link com.ok.wallet.adapter.in.event.dto.SellerPayoutCreatedMessage} */
    public static final String SELLER_PAYOUT_CREATED = "payout.seller-payout-created.v1";

    /** 환불 → 지갑: 예치금 반환 요청. 키는 orderId, 값은 {@link com.ok.wallet.adapter.in.event.dto.RefundApprovedMessage} */
    public static final String REFUND_APPROVED = "refund.refund-approved.v1";

    /** 환불 → 지갑: 돌려준 예치금 되돌리기 요청. 키는 orderId, 값은 {@link com.ok.wallet.adapter.in.event.dto.RefundWalletRevertRequestedMessage} */
    public static final String REFUND_WALLET_REVERT_REQUESTED = "refund.refund-wallet-revert-requested.v1";

    /** 주문 → 지갑: 주문 생성(결제 요청). 키는 orderId, 값은 {@link com.ok.wallet.adapter.in.event.dto.OrderCreatedMessage}. 주문은 처음부터 버전(v1)을 붙인 이름으로만 보낸다 */
    public static final String ORDER_CREATED = "order.order-created.v1";

    /** 주문 → 지갑: 결제 만료 요청. 키는 orderId, 값은 {@link com.ok.wallet.adapter.in.event.dto.OrderPaymentExpireRequestedMessage} */
    public static final String ORDER_PAYMENT_EXPIRE_REQUESTED = "order.order-payment-expire-requested.v1";

    /** 정산 → 지갑: 정산금 입금 확인 요청 묶음. 키는 checkId, 값은 {@link com.ok.wallet.adapter.in.event.dto.PayoutDepositCheckRequestedMessage} */
    public static final String PAYOUT_DEPOSIT_CHECK_REQUESTED = "payout.payout-deposit-check-requested.v1";

    // 공통 설정의 기본 그룹은 앱 이름이라 monolith에서는 모든 모듈이 같은 그룹이 되어 메시지를 나눠 갖는다.
    // 그래서 그룹을 직접 정하고, 리스너마다 나눠 한 리스너만 다시 읽거나 밀린 메시지를 리스너별로 볼 수 있게 한다
    /** 정산금 확정을 받아 판매자 지갑에 입금하는 리스너의 그룹 */
    public static final String PAYOUT_DEPOSIT_GROUP = "wallet.payout-deposit";

    /** 환불 반환 요청을 받아 구매자 지갑에 돌려주는 리스너의 그룹 */
    public static final String REFUND_RETURN_GROUP = "wallet.refund-return";

    /** 환불 되돌리기 요청을 받아 돌려준 예치금을 다시 가져오는 리스너의 그룹 */
    public static final String REFUND_REVERT_GROUP = "wallet.refund-revert";

    /** 정산금 입금 확인 요청을 받아 원장을 읽고 답하는 리스너의 그룹 */
    public static final String DEPOSIT_CHECK_REPLY_GROUP = "wallet.deposit-check-reply";

    /** 주문 생성을 받아 구매자 지갑에서 결제하는 리스너의 그룹 */
    public static final String ORDER_PAYMENT_GROUP = "wallet.order-payment";

    /** 주문의 결제 만료 요청을 받아 결과를 정해 보내는 리스너의 그룹 */
    public static final String PAYMENT_EXPIRE_GROUP = "wallet.payment-expire";

    private WalletKafkaTopics() {
    }
}
