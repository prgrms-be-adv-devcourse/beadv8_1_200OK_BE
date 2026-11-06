package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutSellerDraftRepository;
import com.ok.payout.domain.PayoutSellerDraft;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 판매자 정산 draft 저장소 출력 포트({@link PayoutSellerDraftRepository})의 JPA 구현.
 */
public interface PayoutSellerDraftJpaRepository
        extends JpaRepository<PayoutSellerDraft, Long>, PayoutSellerDraftRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutSellerDraft> List<S> saveAll(Iterable<S> drafts);

    // commitStep: 스냅샷의 판매자 draft를 항목(items)까지 JOIN으로 한 번에 조회
    // 없으면 판매자마다 getItems() 조회가 따로 나가 쿼리가 판매자 수 + 1번 발생 (N+1)
    // 회차(100명) 단위가 아니라 스냅샷 전체를 한 번에 불러옴 (commitStep은 단일 트랜잭션이라 나눌 수 없음)
    @Override
    @EntityGraph(attributePaths = "items")
    List<PayoutSellerDraft> findBySnapshotId(Long snapshotId);
}
