package com.ok.payout.repository;

import com.ok.payout.domain.PayoutFailure;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface PayoutFailureRepository extends Repository<PayoutFailure, Long> {

    List<PayoutFailure> saveAll(Iterable<PayoutFailure> failures);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷의 실패 기록 확인
    List<PayoutFailure> findBySnapshotId(Long snapshotId);
}
