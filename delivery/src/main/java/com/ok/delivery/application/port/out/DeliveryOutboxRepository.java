package com.ok.delivery.application.port.out;

import com.ok.delivery.domain.DeliveryOutbox;
import com.ok.delivery.domain.DeliveryOutboxStatus;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * 배송 outbox 저장소 출력 포트. 구현은 {@code adapter.out.persistence}의 Spring Data 저장소다.
 */
public interface DeliveryOutboxRepository {

    DeliveryOutbox save(DeliveryOutbox outbox);

    Optional<DeliveryOutbox> findById(Long id);

    /**
     * 주어진 상태의 줄을 쌓인 순서(id)대로 읽는다. 같은 주문의 메시지가 쌓인 순서대로 나가게 하려는 것이다.
     */
    List<DeliveryOutbox> findByStatusOrderByIdAsc(DeliveryOutboxStatus status, Pageable pageable);

}
