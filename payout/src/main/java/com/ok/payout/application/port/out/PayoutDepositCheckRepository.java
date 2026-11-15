package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutDepositCheckStatus;

import java.util.Collection;
import java.util.List;

/**
 * 정산·지갑 대사 줄 저장소 출력 포트 (#619). 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutDepositCheckRepository {

    <S extends PayoutDepositCheck> List<S> saveAll(Iterable<S> checks);

    // 매일 대사가 묻는 대기 줄: id가 lastId보다 큰 줄부터 id 순으로 최대 500건(한 질문 묶음, PayoutDepositCheckBatch.MAX_ITEM_COUNT)
    // 앞 묶음의 마지막 id 뒤부터 이어서 읽어, 대기 줄이 많아도 한 번에 모두 메모리에 올리지 않음 (payout/CLAUDE.md 설계 전제)
    List<PayoutDepositCheck> findTop500ByStatusAndIdGreaterThanOrderById(PayoutDepositCheckStatus status, Long lastId);

    // 3번을 다 물었는데 아직 대기인 줄 (마지막 질문의 답이 오지 않은 줄). 매일 이런 줄만 고르므로 수가 적음
    List<PayoutDepositCheck> findAllByStatusAndAttemptCountGreaterThanEqual(PayoutDepositCheckStatus status, int attemptCount);

    List<PayoutDepositCheck> findAllByIdIn(Collection<Long> ids);

    List<PayoutDepositCheck> findAllByPayoutIdIn(Collection<Long> payoutIds);
}
