package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.application.port.out.DeliveryOutboxRepository;
import com.ok.delivery.domain.DeliveryOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    // 즉시 발송과 발송 작업이 같은 줄을 함께 보냈을 때, 둘 다 읽은 값(PENDING)으로 판단하면 둘 다 "보냄"으로 기록해 중복을 알 수 없다.
    // 조건부 UPDATE는 뒤에 온 쪽이 앞 커밋을 기다린 뒤 0줄을 받아 중복을 알아챈다
    @Override
    @Modifying
    @Query("""
            UPDATE DeliveryOutbox o
            SET o.status = com.ok.delivery.domain.DeliveryOutboxStatus.SENT, o.sentAt = :sentAt, o.updatedAt = :sentAt
            WHERE o.id = :id AND o.status = com.ok.delivery.domain.DeliveryOutboxStatus.PENDING
            """)
    int markSentIfPending(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

}
