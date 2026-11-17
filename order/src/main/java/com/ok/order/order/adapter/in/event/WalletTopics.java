package com.ok.order.order.adapter.in.event;

/**
 * 주문이 지갑에게서 Kafka로 받는 토픽 이름과 주문 리스너의 컨슈머 그룹. 토픽 이름은 지갑과의 약속이라 지갑과 같은 값이어야 받는다(#602).
 * 지갑 클래스를 import하지 않고 같은 문자열을 여기 따로 둔다(모듈 경계).
 */
final class WalletTopics {

    // 지갑 → 주문: 결제 완료(예치금 차감). 키는 orderId
    static final String WALLET_PAYMENT_COMPLETED = "wallet.wallet-payment-completed.v1";

    // 지갑 → 주문: 결제 실패. 키는 orderId
    static final String WALLET_PAYMENT_FAILED = "wallet.wallet-payment-failed.v1";

    // 그룹 이름은 <모듈>.<용도>(#648). 정산도 결제 완료를 받으므로 모듈마다 그룹을 나눠야 메시지를 나눠 갖지 않고 각자 모두 받는다.
    // 완료와 실패는 한 주문의 결제 결과라 같은 용도로 묶는다
    static final String PAYMENT_RESULT_GROUP = "order.payment-result";

    private WalletTopics() {
    }
}
