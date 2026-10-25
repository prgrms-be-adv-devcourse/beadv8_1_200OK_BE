package com.ok.payout.repository;

import com.ok.payout.domain.PayoutSnapshotItem;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface PayoutSnapshotItemRepository extends Repository<PayoutSnapshotItem, Long> {

    List<PayoutSnapshotItem> saveAll(Iterable<PayoutSnapshotItem> items);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷 대상 목록 확인
    List<PayoutSnapshotItem> findBySnapshotId(Long snapshotId);
}
