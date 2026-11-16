package com.ok.member.member.adapter.out.event;

/**
 * 회원이 보내는 Kafka 토픽 이름을 한곳에 둔다.
 * 토픽 이름은 받는 모듈(장바구니)과의 약속이라 발행 어댑터와 토픽 선언 설정이 같은 값을 쓰도록 상수로 둔다.
 * 이름 규칙은 {@code <보내는 모듈>.<이벤트>}이다(#339 합의).
 */
public final class MemberTopics {

    /**
     * 회원 → 장바구니: 회원가입이 커밋됨. 키는 memberId, 값은 {@link MemberRegisteredMessage} (#560 메시지 약속).
     * 키를 memberId로 두어 같은 회원의 메시지(앞으로 생길 탈퇴 등)가 같은 파티션으로 가서 순서가 지켜진다.
     */
    public static final String MEMBER_REGISTERED = "member.member-registered";

    /**
     * 회원 → 배송: 판매자 출고지 우편번호가 바뀜(처음 채워진 경우 포함). 키는 sellerId, 값은 {@link SellerOriginPostalCodeMessage} (#587 메시지 약속).
     * 키를 판매자로 두어 같은 판매자의 변경이 한 파티션에서 순서대로 간다. delivery는 occurredAt으로 늦게 온 옛 값을 버린다.
     */
    public static final String SELLER_ORIGIN_POSTAL_CODE = "member.seller-origin-postal-code";

    private MemberTopics() {
    }
}
