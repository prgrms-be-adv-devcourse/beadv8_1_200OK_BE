package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutSnapshot;

import java.util.Optional;

/**
 * 정산 스냅샷 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutSnapshotRepository {

    <S extends PayoutSnapshot> S save(S snapshot);

    // 정산 상세 조회: 정산이 속한 스냅샷의 대상 기간
    Optional<PayoutSnapshot> findById(Long id);
}
