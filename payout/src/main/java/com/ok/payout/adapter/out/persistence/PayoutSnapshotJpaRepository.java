package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutSnapshotRepository;
import com.ok.payout.domain.PayoutSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 정산 스냅샷 저장소 출력 포트({@link PayoutSnapshotRepository})의 JPA 구현.
 * 테스트가 findAll() 등 JpaRepository 기본 메서드로 결과를 확인한다 (예: Job 실행 후 스냅샷이 몇 건 생겼는지).
 */
public interface PayoutSnapshotJpaRepository extends JpaRepository<PayoutSnapshot, Long>, PayoutSnapshotRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    Optional<PayoutSnapshot> findById(Long id);

    @Override
    <S extends PayoutSnapshot> S save(S snapshot);
}
