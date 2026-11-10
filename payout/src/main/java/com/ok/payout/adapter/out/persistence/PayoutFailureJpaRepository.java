package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutFailureRepository;
import com.ok.payout.domain.PayoutFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 정산 실패 기록 저장소 출력 포트({@link PayoutFailureRepository})의 JPA 구현.
 */
public interface PayoutFailureJpaRepository extends JpaRepository<PayoutFailure, Long>, PayoutFailureRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutFailure> List<S> saveAll(Iterable<S> failures);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 스냅샷의 실패 기록 확인
    List<PayoutFailure> findBySnapshotId(Long snapshotId);
}
