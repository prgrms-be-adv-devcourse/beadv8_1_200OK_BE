package com.ok.wallet.repository;

import com.ok.wallet.domain.BuyerWalletLedger;
import com.ok.wallet.domain.BuyerWalletLedgerType;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.ok.wallet.domain.QBuyerWalletLedger.buyerWalletLedger;

/**
 * 구매자 지갑 원장의 이용 내역 조회. 기간·유형은 값이 있을 때만 조건에 더한다.
 * 기간은 [from, toExclusive) 반열린 구간이라, 종료일 하루 전체를 포함하려면 호출하는 쪽이 다음 날 0시를 넘긴다.
 * 유형은 묶음으로 받아, 호출하는 쪽이 보여 줄 유형만 고를 수 있게 한다.
 */
@Repository
@RequiredArgsConstructor
public class BuyerWalletLedgerQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<BuyerWalletLedger> findHistory(
            Long buyerWalletId,
            LocalDateTime from,
            LocalDateTime toExclusive,
            Collection<BuyerWalletLedgerType> types,
            Pageable pageable
    ) {
        BooleanBuilder condition = historyCondition(buyerWalletId, from, toExclusive, types);
        List<BuyerWalletLedger> content = queryFactory
                .selectFrom(buyerWalletLedger)
                .where(condition)
                // 같은 시각에 생긴 내역도 순서가 바뀌지 않도록 id로 한 번 더 정렬한다
                .orderBy(buyerWalletLedger.createdAt.desc(), buyerWalletLedger.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> Objects.requireNonNullElse(queryFactory
                .select(buyerWalletLedger.count())
                .from(buyerWalletLedger)
                .where(condition)
                .fetchOne(), 0L));
    }

    private BooleanBuilder historyCondition(
            Long buyerWalletId, LocalDateTime from, LocalDateTime toExclusive, Collection<BuyerWalletLedgerType> types) {
        BooleanBuilder condition = new BooleanBuilder()
                .and(buyerWalletLedger.buyerWalletId.eq(buyerWalletId));

        if (from != null) {
            condition.and(buyerWalletLedger.createdAt.goe(from));
        }
        if (toExclusive != null) {
            condition.and(buyerWalletLedger.createdAt.lt(toExclusive));
        }
        // null이면 유형으로 거르지 않는다 (서비스는 항상 보여 줄 유형을 넘긴다)
        if (types != null) {
            condition.and(buyerWalletLedger.type.in(types));
        }
        return condition;
    }
}
