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

    // from, to 기간 미정산 후보가 있는 판매자 ID를 먼저 들어온 순으로 limit명 조회. from이 null이면 처음실행이니 전부다
    public List<Long> findUnsettledSellerIds(LocalDateTime from, LocalDateTime to, int limit) {
        return queryFactory
                .select(payoutCandidateItem.sellerId)//판매자아이디 가져오고...
                .from(payoutCandidateItem)// 정산후보테이블 기준
                .where(unsettledInPeriod(from, to))// 공통 조건: 기간 + 미정산
                .groupBy(payoutCandidateItem.sellerId)
                .orderBy(payoutCandidateItem.createdAt.min().asc(),
                        payoutCandidateItem.sellerId.asc())
                .limit(limit)//상한 한번 처리 limit로 처리
                .fetch();
    }

    public List<PayoutCandidateItem> findUnsettledCandidates(List<Long> sellerIds, LocalDateTime from, LocalDateTime to) {
        return queryFactory
                .selectFrom(payoutCandidateItem)
                .where(
                        payoutCandidateItem.sellerId.in(sellerIds),// seller_id 이번 회차 판매자 n명의 후보만 findUnsettledSellerIds 에서 리미트
                        unsettledInPeriod(from, to)) // 공통 조건: 판매자 조회와 반드시 같은 조건
                .orderBy(payoutCandidateItem.id.asc()) // ORDER BY id : 들어온 순서대로 (결과 순서 고정)
                .fetch();
    }

    // 두 쿼리가 공유하는 조건: [from, to) 기간 안 + 아직 정산 안 된 후보
    // 한 곳에서만 정의해서 두 쿼리의 조건이 어긋나지 않게 강제함
    // (어긋나면 판매자는 뽑히는데 후보가 0건 → settle()이 계속 0보다 큰 값 반환 → tasklet 무한 반복)
    private BooleanBuilder unsettledInPeriod(LocalDateTime from, LocalDateTime to) {
        BooleanBuilder condition = new BooleanBuilder();
        if (from != null) {
            condition.and(payoutCandidateItem.createdAt.goe(from));//create_at >= from 기간 범위 지정 (첫 실행이면 하한 없음)
        }
        condition.and(payoutCandidateItem.createdAt.lt(to));//생성후 15일 지난 후보
        condition.and(JPAExpressions.selectOne()
                .from(payoutItem)//payoutitem에서...
                .where(
                        payoutItem.orderItemId.eq(payoutCandidateItem.orderItemId),//주문상품,타입으로
                        payoutItem.eventType.eq(payoutCandidateItem.eventType))
                .notExists());//주문상품번호,타입으로 없는 녀석만 = 미정산 후보
        return condition;
    }
}
