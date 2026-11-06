package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutSnapshotItem;

import java.util.List;

/**
 * 정산 스냅샷 항목 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutSnapshotItemRepository {

    <S extends PayoutSnapshotItem> List<S> saveAll(Iterable<S> items);
}
