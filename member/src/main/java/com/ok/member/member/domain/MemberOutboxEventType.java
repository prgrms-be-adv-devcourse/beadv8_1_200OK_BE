package com.ok.member.member.domain;

/**
 * 회원 outbox로 보내는 메시지 종류. 종류마다 토픽과 메시지 모양이 정해진다.
 */
public enum MemberOutboxEventType {

    /** 회원가입이 커밋됨 (토픽 member.member-registered, #560) */
    MEMBER_REGISTERED

}
