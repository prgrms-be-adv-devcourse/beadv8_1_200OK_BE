package com.ok.order.order.adapter.out.persistence;

import com.ok.order.order.application.port.out.OrderOutboxRepository;
import com.ok.order.order.domain.OrderOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 주문 outbox 저장소 출력 포트의 JPA 구현.
 */
public interface OrderOutboxJpaRepository extends JpaRepository<OrderOutbox, Long>, OrderOutboxRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    Optional<OrderOutbox> findById(Long id);

    @Override
    OrderOutbox save(OrderOutbox outbox);
}
