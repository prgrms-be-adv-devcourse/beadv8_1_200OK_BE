package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutRepository;
import com.ok.payout.domain.Payout;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 정산 저장소 출력 포트({@link PayoutRepository})의 JPA 구현.
 */
public interface PayoutJpaRepository extends JpaRepository<Payout, Long>, PayoutRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends Payout> List<S> saveAll(Iterable<S> items);

    @Override
    Optional<Payout> findById(Long id);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷에서 확정된 정산을 항목(items)까지 함께 조회
    // @EntityGraph: 테스트는 트랜잭션 밖에서 결과를 확인하므로, 나중에 payout.getItems()를 꺼내면
    // 지연 로딩 오류(LazyInitializationException)가 남 → 조회할 때 항목까지 같이 가져옴
    @EntityGraph(attributePaths = "items")
    List<Payout> findBySnapshotId(Long snapshotId);
}
