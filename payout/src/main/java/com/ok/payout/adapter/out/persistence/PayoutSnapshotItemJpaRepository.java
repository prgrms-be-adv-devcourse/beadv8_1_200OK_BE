package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutSnapshotItemRepository;
import com.ok.payout.domain.PayoutSnapshotItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 정산 스냅샷 항목 저장소 출력 포트({@link PayoutSnapshotItemRepository})의 JPA 구현.
 */
public interface PayoutSnapshotItemJpaRepository
        extends JpaRepository<PayoutSnapshotItem, Long>, PayoutSnapshotItemRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutSnapshotItem> List<S> saveAll(Iterable<S> items);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷 대상 목록 확인
    List<PayoutSnapshotItem> findBySnapshotId(Long snapshotId);
}
