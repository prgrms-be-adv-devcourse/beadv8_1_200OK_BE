package com.ok.payout.repository;

import com.ok.payout.domain.PayoutSellerDraft;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface PayoutSellerDraftRepository extends Repository<PayoutSellerDraft, Long> {

    List<PayoutSellerDraft> saveAll(Iterable<PayoutSellerDraft> drafts);

    // commitStep: 스냅샷의 판매자 draft를 항목(items)까지 JOIN으로 한 번에 조회
    // 없으면 판매자마다 getItems() 조회가 따로 나가 쿼리가 판매자 수 + 1번 발생 (N+1)
    // 회차(100명) 단위가 아니라 스냅샷 전체를 한 번에 불러옴 (commitStep은 단일 트랜잭션이라 나눌 수 없음)
    @EntityGraph(attributePaths = "items")
    List<PayoutSellerDraft> findBySnapshotId(Long snapshotId);
}
