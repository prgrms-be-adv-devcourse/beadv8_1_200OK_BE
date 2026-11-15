package com.ok.delivery.config;

/**
 * 배송이 Kafka로 받는 토픽 이름과 배송 리스너의 컨슈머 그룹을 한곳에 둔다.
 * 토픽 이름은 다른 모듈과의 약속이라, 수신 코드와 토픽 생성 설정(config)이 같은 값을 쓰도록 상수로 둔다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다.
 */
public final class DeliveryTopics {

    /** member → 배송: 판매자 출고지 우편번호. 키는 sellerId, 값은 {@link com.ok.delivery.adapter.in.event.dto.SellerOriginPostalCodeMessage} */
    public static final String SELLER_ORIGIN_POSTAL_CODE = "member.seller-origin-postal-code";

    /** 배송 리스너의 컨슈머 그룹. 모듈마다 나눠야 같은 메시지를 모듈마다 받는다 */
    public static final String CONSUMER_GROUP = "delivery";

    private DeliveryTopics() {
    }
}
