package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 배송 outbox 저장소 출력 포트. 구현은 {@code adapter.out.persistence}의 Spring Data 저장소다.
 */
public interface DeliveryOutboxRepository {

    DeliveryOutbox save(DeliveryOutbox outbox);

    Optional<DeliveryOutbox> findById(Long id);

    /**
     * 주어진 상태이고 {@code createdBefore}보다 먼저 남은 줄을 쌓인 순서(id)대로 읽는다. 같은 주문의 메시지가 쌓인 순서대로 나가게 하려는 것이다.
     */
    List<DeliveryOutbox> findByStatusAndCreatedAtBeforeOrderByIdAsc(DeliveryOutboxStatus status, LocalDateTime createdBefore,
                                                                     Pageable pageable);

    /**
     * 아직 PENDING인 줄만 SENT로 바꾼다. 다른 발송이 먼저 바꿨으면 바꾸지 않는다.
     *
     * @return 바꾼 줄 수(0 또는 1)
     */
    int markSentIfPending(Long id, LocalDateTime sentAt);

}
