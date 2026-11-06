package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutSystemDraftItem;

import java.util.List;

/**
 * 시스템 몫(수수료) draft 항목 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutSystemDraftItemRepository {

    <S extends PayoutSystemDraftItem> List<S> saveAll(Iterable<S> items);

    // commitStep: 스냅샷의 수수료 항목 전체 (합쳐서 시스템 몫 payout 1건)
    List<PayoutSystemDraftItem> findBySnapshotId(Long snapshotId);
}
