package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutOutboxRepository;
import com.ok.payout.application.port.out.PayoutOutboxRow;
import com.ok.payout.domain.PayoutOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 정산 outbox 저장소 출력 포트({@link PayoutOutboxRepository})의 JPA 구현.
 */
public interface PayoutOutboxJpaRepository extends JpaRepository<PayoutOutbox, Long>, PayoutOutboxRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    PayoutOutbox save(PayoutOutbox outbox);

    @Override
    <S extends PayoutOutbox> List<S> saveAll(Iterable<S> outboxes);

    @Override
    Optional<PayoutOutbox> findById(Long id);

    // 덜 시도한 줄부터: 매번 실패하는 줄이 앞에 몰려 연속 실패로 멈춰도 뒤 줄이 막히지 않게
    @Override
    @Query("""
            select new com.ok.payout.application.port.out.PayoutOutboxRow(o.id, p.id, p.payeeId, p.amount, p.createdAt)
            from PayoutOutbox o join Payout p on p.id = o.payoutId
            where o.status = com.ok.payout.domain.PayoutOutboxStatus.PENDING
            order by o.attemptCount, o.id
            """)
    List<PayoutOutboxRow> findPendingRows();
}
