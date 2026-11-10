package com.ok.payout.application.port.out;

import com.ok.payout.domain.PayoutSellerDraft;

import java.util.List;

/**
 * 판매자 정산 draft 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface PayoutSellerDraftRepository {

    <S extends PayoutSellerDraft> List<S> saveAll(Iterable<S> drafts);

    // commitStep: 스냅샷의 판매자 draft를 항목(items)까지 함께 조회 (쿼리 설명은 어댑터의 해당 메서드에 있다)
    List<PayoutSellerDraft> findBySnapshotId(Long snapshotId);
}
