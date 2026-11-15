package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutDepositCheckBatch;

/**
 * 대사 질문 묶음 저장소 출력 포트 (#619). 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutDepositCheckBatchRepository {

    PayoutDepositCheckBatch save(PayoutDepositCheckBatch batch);
}
