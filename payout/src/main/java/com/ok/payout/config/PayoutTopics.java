package com.ok.payout.config;

/**
 * 정산이 보내고 받는 Kafka 토픽 이름과 정산 리스너의 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 다른 모듈과의 약속이라, 발행·수신 코드와 토픽 생성 설정(config)이 같은 값을 쓰도록 상수로 둔다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다 (#339).
 */
public final class PayoutTopics {

    /** 정산 → 지갑: 판매자에게 줄 정산금이 확정됨. 지갑이 받아 판매자 지갑에 입금한다. 키는 payoutId, 값은 {@link com.ok.payout.event.dto.SellerPayoutCreatedMessage} */
    public static final String SELLER_PAYOUT_CREATED = "payout.seller-payout-created";

    // 정산이 받는 토픽. 이름은 보내는 모듈(주문·지갑)의 것이라 보내는 쪽과 같은 값이어야 받는다(#339 합의)

    /** 주문 → 정산: 구매확정. 정산이 받아 정산 후보를 만든다. 키는 orderItemId, 값은 {@link com.ok.payout.event.dto.OrderProductConfirmedMessage} */
    public static final String ORDER_PRODUCT_CONFIRMED = "order.order-product-confirmed";

    /** 지갑 → 정산: 결제 완료(예치금 차감). 정산이 받아 차감 기록을 남긴다. 키는 orderId, 값은 {@link com.ok.payout.event.dto.WalletPaymentCompletedMessage} */
    public static final String WALLET_PAYMENT_COMPLETED = "wallet.wallet-payment-completed";

    /** 정산 리스너의 컨슈머 그룹. 모듈마다 나눠야 같은 메시지를 모듈마다 받는다(#339 합의) */
    public static final String CONSUMER_GROUP = "payout";

    private PayoutTopics() {
    }
}
