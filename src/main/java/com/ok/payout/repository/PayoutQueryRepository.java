package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.ok.payout.domain.QPayoutCandidateItem.payoutCandidateItem;
import static com.ok.payout.domain.QPayoutItem.payoutItem;

@Repository
@RequiredArgsConstructor
public class PayoutQueryRepository {

    private final JPAQueryFactory queryFactory;

    // [from, to) 기간 미정산 후보가 있는 판매자 ID를 가장 먼저 들어온 후보 순으로 limit명 조회 (from이 null이면 하한 없음)
    public List<Long> findUnsettledSellerIds(LocalDateTime from, LocalDateTime to, int limit) {
        return queryFactory
                .select(payoutCandidateItem.sellerId)
                .from(payoutCandidateItem)
                .where(unsettledInPeriod(from, to))
                .groupBy(payoutCandidateItem.sellerId)
                .orderBy(payoutCandidateItem.createdAt.min().asc(),
                        payoutCandidateItem.sellerId.asc())
                .limit(limit)
                .fetch();
    }

    // 지정한 판매자들의 [from, to) 기간 미정산 후보 전체 조회 (대금·배송비·수수료 3종 모두)
    public List<PayoutCandidateItem> findUnsettledCandidates(List<Long> sellerIds, LocalDateTime from, LocalDateTime to) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        payoutCandidateItem.sellerId.in(sellerIds),
                        unsettledInPeriod(from, to)) // 판매자 조회와 반드시 같은 조건
                .orderBy(payoutCandidateItem.id.asc())
                .fetch();
    }

    // 두 쿼리가 공유하는 조건: [from, to) 기간 안 + 아직 정산 안 된 후보
    // 한 곳에서만 정의해서 두 쿼리의 조건이 어긋나지 않게 강제함
    // (어긋나면 판매자는 뽑히는데 후보가 0건 → settle()이 계속 0보다 큰 값 반환 → tasklet 무한 반복)
    private BooleanBuilder unsettledInPeriod(LocalDateTime from, LocalDateTime to) {
        BooleanBuilder condition = new BooleanBuilder();
        if (from != null) {
            condition.and(payoutCandidateItem.createdAt.goe(from));
        }
        condition.and(payoutCandidateItem.createdAt.lt(to));
        // payout_item에 같은 (order_item_id, event_type)이 없으면 미정산
        condition.and(JPAExpressions.selectOne()
                .from(payoutItem)
                .where(
                        payoutItem.orderItemId.eq(payoutCandidateItem.orderItemId),
                        payoutItem.eventType.eq(payoutCandidateItem.eventType))
                .notExists());
        return condition;
    }
}
