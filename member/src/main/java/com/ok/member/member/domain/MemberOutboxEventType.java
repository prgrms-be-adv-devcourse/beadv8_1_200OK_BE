package com.ok.member.member.domain;

/**
 * 회원 outbox로 보내는 메시지 종류. 종류마다 토픽과 메시지 모양이 정해진다.
 */
public enum MemberOutboxEventType {

    /** 회원가입이 커밋됨 (토픽 member.member-registered, #560). 본문 없이 회원 ID만 보낸다 */
    MEMBER_REGISTERED,

    /** 판매자 출고지 우편번호가 바뀜 (토픽 member.seller-origin-postal-code, #587). 본문에 우편번호와 바뀐 시각을 담는다 */
    SELLER_ORIGIN_POSTAL_CODE_CHANGED

}
