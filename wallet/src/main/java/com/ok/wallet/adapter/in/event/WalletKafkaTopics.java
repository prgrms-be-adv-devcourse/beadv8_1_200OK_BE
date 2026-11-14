package com.ok.wallet.adapter.in.event;

/**
 * 지갑이 받는 Kafka 토픽 이름과 지갑 리스너의 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 보내는 모듈과의 약속이라 보내는 쪽과 같은 값이어야 받는다. 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다(#339).
 */
public final class WalletKafkaTopics {

    /** 정산 → 지갑: 판매자 정산금 확정. 키는 payoutId, 값은 {@link com.ok.wallet.adapter.in.event.dto.SellerPayoutCreatedMessage} */
    public static final String SELLER_PAYOUT_CREATED = "payout.seller-payout-created";

    // 공통 설정의 기본 그룹은 앱 이름이라 monolith에서는 모든 모듈이 같은 그룹이 되어 메시지를 나눠 갖는다.
    // 같은 토픽을 받는 다른 모듈과 따로 받도록 모듈 이름으로 정해 둔다(#339 합의)
    public static final String CONSUMER_GROUP = "wallet";

    private WalletKafkaTopics() {
    }
}
