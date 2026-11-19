package com.ok.order.order.application.port.out;

import com.ok.order.order.domain.OrderOutbox;
import com.ok.order.order.domain.OrderOutboxStatus;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 outbox 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface OrderOutboxRepository {

    OrderOutbox save(OrderOutbox outbox);

    Optional<OrderOutbox> findById(Long id);

    /**
     * 상태가 {@code status}이고 {@code createdBefore} 전에 남긴 줄을 쌓인 순서(id)대로 조회한다.
     */
    List<OrderOutbox> findByStatusAndCreatedAtBeforeOrderByIdAsc(OrderOutboxStatus status, LocalDateTime createdBefore,
                                                                  Pageable pageable);
}
