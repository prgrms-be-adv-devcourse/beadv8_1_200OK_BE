package com.ok.order.order.application.service;

import com.ok.order.order.application.dto.OrderOutboxMessage;
import com.ok.order.order.application.port.out.OrderOutboxRepository;
import com.ok.order.order.domain.OrderOutbox;
import com.ok.order.order.domain.OrderOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 outbox 줄을 남기고, 읽고, 발송 결과를 저장한다 (#666).
 * 발송은 {@link OrderOutboxRelayFacade}가 트랜잭션 밖에서 하고, 이 서비스는 그 앞뒤의 짧은 DB 작업만 맡는다.
 * 결과는 줄마다 따로 저장해, 뒤 줄의 실패가 앞 줄의 "보냄"을 되돌리지 않게 한다(회원·정산 outbox와 같음).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderOutboxService {

    private final OrderOutboxRepository orderOutboxRepository;
    // 보낸 시각 기준 (전역 ClockConfig)
    private final Clock clock;

    // MANDATORY: 부른 쪽(주문 변경)의 트랜잭션에 합류하고, 트랜잭션이 없으면 예외를 낸다.
    // 따로 커밋되면 롤백된 주문 변경의 메시지가 나가거나, 커밋된 변경의 메시지가 안 나갈 수 있어서 같은 트랜잭션을 강제한다
    @Transactional(propagation = Propagation.MANDATORY)
    Long save(String topic, String messageKey, String payload) {
        return orderOutboxRepository.save(OrderOutbox.pending(topic, messageKey, payload)).getId();
    }

    List<OrderOutboxMessage> findPending(int limit) {
        return orderOutboxRepository.findByStatusOrderByIdAsc(OrderOutboxStatus.PENDING, PageRequest.ofSize(limit))
                .stream()
                .map(OrderOutboxService::toMessage)
                .toList();
    }

    // 커밋 직후 발송 대상. 그사이 재발송이 먼저 보냈으면 비어 있다
    Optional<OrderOutboxMessage> findPendingById(Long outboxId) {
        return orderOutboxRepository.findById(outboxId)
                .filter(OrderOutbox::isPending)
                .map(OrderOutboxService::toMessage);
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
    private OrderOutbox get(Long outboxId) {
        return orderOutboxRepository.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("주문 outbox 줄이 없습니다. outboxId=" + outboxId));
    }

    private static OrderOutboxMessage toMessage(OrderOutbox outbox) {
        return new OrderOutboxMessage(outbox.getId(), outbox.getTopic(), outbox.getMessageKey(), outbox.getPayload(),
                outbox.getAttemptCount());
    }
}
