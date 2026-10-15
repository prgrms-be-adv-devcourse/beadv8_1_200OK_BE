package com.ok.payout.repository;

import com.ok.payout.domain.Payout;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends Repository<Payout, Long> {

    Payout save(Payout item);

    List<Payout> saveAll(Iterable<Payout> items);

    // 가장 최근 정산 1건
    Optional<Payout> findFirstByOrderByIdDesc();

    // publishStep: 이번 스냅샷에서 확정된 판매자 몫 정산만 (시스템 몫은 지갑으로 보내지 않음)
    List<Payout> findBySnapshotIdAndSystemFalse(Long snapshotId);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷에서 확정된 정산을 항목(items)까지 함께 조회
    // @EntityGraph: 테스트는 트랜잭션 밖에서 결과를 확인하므로, 나중에 payout.getItems()를 꺼내면
    // 지연 로딩 오류(LazyInitializationException)가 남 → 조회할 때 항목까지 같이 가져옴
    @EntityGraph(attributePaths = "items")
    List<Payout> findBySnapshotId(Long snapshotId);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 정산 전체 건수
    long count();

}
