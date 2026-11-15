package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutOutbox;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 정산 outbox 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutOutboxRepository {

    PayoutOutbox save(PayoutOutbox outbox);

    <S extends PayoutOutbox> List<S> saveAll(Iterable<S> outboxes);

    Optional<PayoutOutbox> findById(Long id);

    // 대사 답 묶음(최대 500건)의 "입금 없음" 정산 줄을 한 번에 찾음 (#620). payout_id에 UK가 있어 인덱스로 찾음
    List<PayoutOutbox> findAllByPayoutIdIn(Collection<Long> payoutIds);

    // 아직 보내지 않은(PENDING) 줄을 덜 시도한 줄부터(같으면 id 순), 보낼 메시지에 필요한 정산 값과 함께
    List<PayoutOutboxRow> findPendingRows();
}
