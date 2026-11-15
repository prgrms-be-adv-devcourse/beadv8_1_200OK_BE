package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutDepositCheckBatchRepository;
import com.ok.payout.domain.PayoutDepositCheckBatch;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 대사 질문 묶음 저장소 출력 포트({@link PayoutDepositCheckBatchRepository})의 JPA 구현 (#619).
 */
public interface PayoutDepositCheckBatchJpaRepository extends JpaRepository<PayoutDepositCheckBatch, Long>, PayoutDepositCheckBatchRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    PayoutDepositCheckBatch save(PayoutDepositCheckBatch batch);
}
