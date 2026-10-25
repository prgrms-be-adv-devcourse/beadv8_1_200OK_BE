package com.ok.payout.repository;

import com.ok.payout.domain.PayoutSnapshot;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface PayoutSnapshotRepository extends Repository<PayoutSnapshot, Long> {

    PayoutSnapshot save(PayoutSnapshot snapshot);

    // 정산 상세 조회: 정산이 속한 스냅샷의 대상 기간
    Optional<PayoutSnapshot> findById(Long id);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): Job 실행 후 스냅샷이 몇 건 생겼는지 확인
    List<PayoutSnapshot> findAll();
}
