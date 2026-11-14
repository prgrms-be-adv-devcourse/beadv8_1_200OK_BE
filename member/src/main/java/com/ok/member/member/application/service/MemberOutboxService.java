package com.ok.member.member.application.service;

import com.ok.member.member.application.dto.MemberOutboxMessage;
import com.ok.member.member.application.port.in.MemberOutboxRecordUseCase;
import com.ok.member.member.application.port.out.MemberMessagePayloadWriter;
import com.ok.member.member.application.port.out.MemberOutboxRepository;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 회원 outbox 줄을 남기고, 읽고, 발송 결과를 저장한다 (#560, #587).
 * 발송은 {@link MemberOutboxRelayFacade}가 트랜잭션 밖에서 하고, 이 서비스는 그 앞뒤의 짧은 DB 작업만 맡는다.
 * 결과는 줄마다 따로 저장해, 뒤 줄의 실패가 앞 줄의 "보냄"을 되돌리지 않게 한다(정산 outbox와 같음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberOutboxService implements MemberOutboxRecordUseCase {

    private final MemberOutboxRepository memberOutboxRepository;
    private final MemberMessagePayloadWriter memberMessagePayloadWriter;
    // 보낸 시각 기준 (전역 ClockConfig)
    private final Clock clock;

    // MANDATORY: 호출한 쪽(판매자 프로필 저장)의 트랜잭션에 합류하고, 트랜잭션이 없으면 예외를 낸다.
    // 따로 커밋되면 롤백된 프로필 변경이 나가거나, 커밋된 변경이 안 나갈 수 있어서 같은 트랜잭션을 강제한다
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSellerOriginPostalCodeChanged(Long sellerId, String postalCode, LocalDateTime occurredAt) {
        String payload = memberMessagePayloadWriter.writeSellerOriginPostalCode(sellerId, postalCode, occurredAt);
        memberOutboxRepository.save(MemberOutbox.sellerOriginPostalCodeChanged(sellerId, payload));
    }

    List<MemberOutboxMessage> findPending(int limit) {
        return memberOutboxRepository.findByStatusOrderByIdAsc(MemberOutboxStatus.PENDING, PageRequest.ofSize(limit))
                .stream()
                .map(outbox -> new MemberOutboxMessage(outbox.getId(), outbox.getEventType(), outbox.getMemberId(),
                        outbox.getPayload(), outbox.getAttemptCount()))
                .toList();
    }

    @Transactional
    void markSent(Long outboxId) {
        get(outboxId).markSent(LocalDateTime.now(clock));
    }

    @Transactional
    void recordFailure(Long outboxId, String error) {
        get(outboxId).recordFailure(error);
    }

    // 방금 읽은 줄이 없으면 누가 지운 것(버그)이라 표준 예외로 알린다 (exception.md: 값의 출처 기준)
    private MemberOutbox get(Long outboxId) {
        return memberOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("회원 outbox 줄이 없습니다. outboxId=" + outboxId));
    }

}
