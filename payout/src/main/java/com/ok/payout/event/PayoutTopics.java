package com.ok.payout.event;

/**
 * 정산이 보내는 Kafka 토픽 이름을 한곳에 둔다.
 * 토픽 이름은 받는 모듈과의 약속이라, 발행 구현체와 토픽 생성 설정(config)이 같은 값을 쓰도록 상수로 둔다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다 (#339).
 */
public final class PayoutTopics {

    /** 판매자 몫 정산 확정. 키는 payoutId, 값은 {@link com.ok.payout.event.dto.SellerPayoutCreatedMessage} */
    public static final String SELLER_PAYOUT_CREATED = "payout.seller-payout-created";

    private PayoutTopics() {
    }
}
