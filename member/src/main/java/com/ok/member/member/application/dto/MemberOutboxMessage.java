package com.ok.member.member.application.dto;

import com.ok.member.member.domain.MemberOutboxEventType;

/**
 * 보낼 차례인 outbox 줄 하나. 발송은 트랜잭션 밖에서 하므로 엔티티 대신 보낼 때 필요한 값만 넘긴다.
 *
 * @param memberId     메시지 키. 판매자 메시지면 판매자인 회원의 ID
 * @param payload      저장해 둔 메시지 본문(JSON). 회원 ID만 보내는 메시지는 {@code null}
 * @param attemptCount 지금까지 실패한 발송 횟수. 계속 실패하는 줄을 알아보는 데 쓴다
 */
public record MemberOutboxMessage(Long outboxId, MemberOutboxEventType eventType, Long memberId, String payload,
                                  int attemptCount) {
}
