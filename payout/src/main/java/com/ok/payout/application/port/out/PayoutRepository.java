package com.ok.payout.application.port.out;

import com.ok.payout.domain.Payout;

import java.util.List;
import java.util.Optional;

/**
 * 정산 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutRepository {

    <S extends Payout> List<S> saveAll(Iterable<S> items);

    // 가장 최근 정산 1건
    Optional<Payout> findFirstByOrderByIdDesc();

    // 정산 상세 조회
    Optional<Payout> findById(Long id);

    // 정산 상세: 판매자 정산과 같은 스냅샷의 시스템 몫 정산(수수료 묶음). commitStep이 스냅샷당 최대 1건만 만듦
    Optional<Payout> findBySnapshotIdAndSystemTrue(Long snapshotId);

    // 이번 스냅샷에서 확정된 판매자 몫 정산만 (지금은 통합 테스트 검증에서만 씀)
    List<Payout> findBySnapshotIdAndSystemFalse(Long snapshotId);
}
