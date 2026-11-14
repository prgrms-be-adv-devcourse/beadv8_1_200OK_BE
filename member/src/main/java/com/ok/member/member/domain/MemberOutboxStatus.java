package com.ok.member.member.domain;

/**
 * 회원 outbox 줄의 발송 상태.
 */
public enum MemberOutboxStatus {

    /** 아직 보내지 않았거나 보내다 실패해 다음 실행에서 다시 보낼 줄 */
    PENDING,

    /** 브로커가 저장을 확인한 줄 */
    SENT

}
