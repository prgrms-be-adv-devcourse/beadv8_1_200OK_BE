package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.DeliveryOutboxMessage;
import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 배송 outbox 줄을 읽고 발송 결과를 저장한다 (#644).
 * 발송은 {@link DeliveryOutboxRelayFacade}가 트랜잭션 밖에서 하고, 이 서비스는 그 앞뒤의 짧은 DB 작업만 맡는다.
 * 결과는 줄마다 따로 저장해, 뒤 줄의 실패가 앞 줄의 "보냄"을 되돌리지 않게 한다(회원·정산 outbox와 같음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryOutboxService {

    private final DeliveryOutboxRepository deliveryOutboxRepository;
    private final Clock clock;

    List<DeliveryOutboxMessage> findPending(int limit, Duration minAge) {
        LocalDateTime createdBefore = LocalDateTime.now(clock).minus(minAge);
        return deliveryOutboxRepository.findByStatusAndCreatedAtBeforeOrderByIdAsc(
                        DeliveryOutboxStatus.PENDING, createdBefore, PageRequest.ofSize(limit))
                .stream()
                .map(DeliveryOutboxService::toMessage)
                .toList();
    }

    Optional<DeliveryOutboxMessage> findPendingById(Long outboxId) {
        return deliveryOutboxRepository.findById(outboxId)
                .filter(outbox -> outbox.getStatus() == DeliveryOutboxStatus.PENDING)
                .map(DeliveryOutboxService::toMessage);
    }

    /**
     * 줄을 "보냄"으로 바꾼다.
     *
     * @return 다른 발송이 먼저 "보냄"으로 바꿨으면 false. 이때 메시지는 두 번 나간 것이다
     */
    @Transactional
    boolean markSent(Long outboxId) {
        return deliveryOutboxRepository.markSentIfPending(outboxId, LocalDateTime.now(clock)) == 1;
    }

    @Transactional
    void recordFailure(Long outboxId, String error) {
        get(outboxId).recordFailure(error);
    }

    // 방금 읽은 줄이 없으면 누가 지운 것(버그)이라 표준 예외로 알린다 (exception.md: 값의 출처 기준)
    private DeliveryOutbox get(Long outboxId) {
        return deliveryOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("배송 outbox 줄이 없습니다. outboxId=" + outboxId));
    }

    private static DeliveryOutboxMessage toMessage(DeliveryOutbox outbox) {
        return new DeliveryOutboxMessage(outbox.getId(), outbox.getEventType(), outbox.getMessageKey(),
                outbox.getPayload(), outbox.getAttemptCount());
    }

}
