package com.ok.member.member.domain;

/**
 * 회원 outbox로 보내는 메시지 종류. 종류마다 토픽과 메시지 모양이 정해진다.
 */
public enum MemberOutboxEventType {

    /** 회원가입이 커밋됨 (토픽 member.member-registered.v1, #560). 본문 없이 회원 ID만 보낸다 */
    MEMBER_REGISTERED,

    /**
     * 판매자 출고지 우편번호를 알림 (토픽 member.seller-origin-postal-code.v1, #587). 본문에 우편번호와 시각을 담는다.
     * 처음에는 바뀔 때만 보내 이름이 CHANGED지만, 이제는 프로필 저장마다 보낸다(#651). DB에 이 이름으로 쌓인 줄이 있어 바꾸지 않는다
     */
    SELLER_ORIGIN_POSTAL_CODE_CHANGED

}
