package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
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
                .where(
                        from == null ? null : payoutCandidateItem.createdAt.goe(from),//create_at >= from 기간 범위 지정
                        payoutCandidateItem.createdAt.lt(to),//생성후 15일 지난 후보
                        JPAExpressions.selectOne()
                                .from(payoutItem)//payoutitem에서...
                                .where(
                                        payoutItem.orderItemId.eq(payoutCandidateItem.orderItemId),//주문상품,타입으로
                                        payoutItem.eventType.eq(payoutCandidateItem.eventType))
                                .notExists())//주문상품번호,타입으로 엇는 녀석만 후보로 이동
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
                        from == null ? null : payoutCandidateItem.createdAt.goe(from), // created_at >= from : 하한 (판매자 조회와 같은 범위여야 함)
                        payoutCandidateItem.createdAt.lt(to),//to가 하향선 마지막 범위
                        JPAExpressions.selectOne()
                                .from(payoutItem)
                                .where(
                                        payoutItem.orderItemId.eq(payoutCandidateItem.orderItemId),//아이디와 타입으로
                                        payoutItem.eventType.eq(payoutCandidateItem.eventType))
                                .notExists()) // 미정산 후보만
                .orderBy(payoutCandidateItem.id.asc()) // ORDER BY id : 들어온 순서대로 (결과 순서 고정)
                .fetch();
    }
}
