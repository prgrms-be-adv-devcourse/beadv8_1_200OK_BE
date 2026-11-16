package com.ok.payout.config;

/**
 * 정산이 보내고 받는 Kafka 토픽 이름과 정산 리스너의 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 다른 모듈과의 약속이라, 발행·수신 코드와 토픽 생성 설정(config)이 같은 값을 쓰도록 상수로 둔다.
 * 토픽 이름 규칙은 {@code <보내는 모듈>.<이벤트>.v<버전>}이다 (#648). 버전이 없는 이름은 아직 옮기기 전인 토픽이다 (#653).
 * 컨슈머 그룹 규칙은 {@code <모듈>.<용도>}로 리스너마다 하나다 (#648).
 */
public final class PayoutTopics {

    /** 정산 → 지갑: 판매자에게 줄 정산금이 확정됨. 지갑이 받아 판매자 지갑에 입금한다. 키는 payoutId, 값은 {@link com.ok.payout.adapter.out.event.dto.SellerPayoutCreatedMessage} */
    public static final String SELLER_PAYOUT_CREATED = "payout.seller-payout-created";

    /** 정산 → 지갑: 대사 질문 묶음("이 정산들, 입금됐나요?", #619). 키는 checkId, 값은 {@link com.ok.payout.adapter.out.event.dto.PayoutDepositCheckRequestedMessage} */
    public static final String PAYOUT_DEPOSIT_CHECK_REQUESTED = "payout.payout-deposit-check-requested";

    // 정산이 받는 토픽. 이름은 보내는 모듈(주문·지갑)의 것이라 보내는 쪽과 같은 값이어야 받는다(#339 합의)

    /** 주문 → 정산: 구매확정. 정산이 받아 정산 후보를 만든다. 키는 orderItemId, 값은 {@link com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage} */
    // 주문은 처음부터 .v1로만 보낸다(#648 주문 댓글, #600). 주문이 아직 Kafka로 보내지 않아 옛 이름에는 흐르는 메시지가 없어 이름만 바꾼다 (#661)
    public static final String ORDER_PRODUCT_CONFIRMED = "order.order-product-confirmed.v1";

    /** 지갑 → 정산: 결제 완료(예치금 차감). 정산이 받아 차감 기록을 남긴다. 키는 orderId, 값은 {@link com.ok.payout.adapter.in.event.dto.WalletPaymentCompletedMessage} */
    public static final String WALLET_PAYMENT_COMPLETED = "wallet.wallet-payment-completed";

    /** 지갑 → 정산: 대사 답 묶음(#619, 지갑 #613). 키는 checkId, 값은 {@link com.ok.payout.adapter.in.event.dto.PayoutDepositCheckedMessage} */
    public static final String WALLET_PAYOUT_DEPOSIT_CHECKED = "wallet.payout-deposit-checked";

    /** 구매확정 리스너의 컨슈머 그룹. 이 리스너만 읽은 위치를 되돌리거나 다시 붙어도 다른 정산 리스너가 멈추지 않게 따로 둔다 (#648, #661) */
    public static final String CANDIDATE_CREATE_GROUP = "payout.candidate-create";

    /**
     * 결제 완료·대사 답 리스너가 함께 쓰는 컨슈머 그룹. 모듈마다 나눠야 같은 메시지를 모듈마다 받는다(#339 합의).
     * 두 리스너는 받는 지갑 토픽이 .v1로 바뀔 때 리스너마다 새 그룹으로 옮긴다 (#653).
     */
    public static final String CONSUMER_GROUP = "payout";

    private PayoutTopics() {
    }
}
