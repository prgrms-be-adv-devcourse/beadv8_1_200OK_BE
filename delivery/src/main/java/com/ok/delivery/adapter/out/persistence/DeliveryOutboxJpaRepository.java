package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 배송 outbox 저장소 출력 포트({@link DeliveryOutboxRepository})의 JPA 구현.
 */
public interface DeliveryOutboxJpaRepository extends JpaRepository<DeliveryOutbox, Long>, DeliveryOutboxRepository {

    // 포트와 JpaRepository가 같은 메서드를 다른 제네릭 형태로 물려줘 모호한 호출이 되므로 다시 선언해 하나로 합친다

    @Override
    DeliveryOutbox save(DeliveryOutbox outbox);

    @Override
    Optional<DeliveryOutbox> findById(Long id);

}
