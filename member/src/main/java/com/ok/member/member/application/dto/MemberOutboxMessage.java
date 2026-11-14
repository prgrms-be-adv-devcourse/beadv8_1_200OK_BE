package com.ok.member.member.application.dto;

import com.ok.member.member.domain.MemberOutboxEventType;

/**
 * 보낼 차례인 outbox 줄 하나. 발송은 트랜잭션 밖에서 하므로 엔티티 대신 보낼 때 필요한 값만 넘긴다.
 *
 * @param attemptCount 지금까지 실패한 발송 횟수. 계속 실패하는 줄을 알아보는 데 쓴다
 */
public record MemberOutboxMessage(Long outboxId, MemberOutboxEventType eventType, Long memberId, int attemptCount) {
}
