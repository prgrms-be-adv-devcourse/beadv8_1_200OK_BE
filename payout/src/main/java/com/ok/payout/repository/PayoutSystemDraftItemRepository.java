package com.ok.payout.repository;

import com.ok.payout.domain.PayoutSystemDraftItem;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface PayoutSystemDraftItemRepository extends Repository<PayoutSystemDraftItem, Long> {

    List<PayoutSystemDraftItem> saveAll(Iterable<PayoutSystemDraftItem> items);

    // commitStep: 스냅샷의 수수료 항목 전체 (합쳐서 시스템 몫 payout 1건)
    List<PayoutSystemDraftItem> findBySnapshotId(Long snapshotId);
}
