package com.ok.delivery.adapter.out.persistence;

import com.ok.delivery.domain.Shipment;
import com.ok.delivery.domain.ShipmentItem;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.ok.delivery.domain.QShipment.shipment;
import static com.ok.delivery.domain.QShipmentItem.shipmentItem;

/**
 * 배송 목록 조회. 목록에 보여 줄 상품은 shipment마다 따로 읽지 않도록 페이지 단위로 한 번에 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class ShipmentQueryRepository {

    private final JPAQueryFactory queryFactory;

    /**
     * 구매자의 shipment를 최신순으로 조회한다.
     */
    public Page<Shipment> findByBuyerId(Long buyerId, Pageable pageable) {
        return findLatest(shipment.buyerId.eq(buyerId), pageable);
    }

    /**
     * 판매자의 shipment를 최신순으로 조회한다.
     */
    public Page<Shipment> findBySellerId(Long sellerId, Pageable pageable) {
        return findLatest(shipment.sellerId.eq(sellerId), pageable);
    }

    private Page<Shipment> findLatest(BooleanExpression condition, Pageable pageable) {
        List<Shipment> content = queryFactory
                .selectFrom(shipment)
                .where(condition)
                // 같은 결제로 함께 만들어진 shipment도 순서가 바뀌지 않도록 id로 한 번 더 정렬한다
                .orderBy(shipment.createdAt.desc(), shipment.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        return PageableExecutionUtils.getPage(content, pageable, () -> Objects.requireNonNullElse(queryFactory
                .select(shipment.count())
                .from(shipment)
                .where(condition)
                .fetchOne(), 0L));
    }

    /**
     * 여러 shipment의 상품 항목을 한 번에 조회한다. 항목은 shipment 안에서 만든 순서(id)대로다.
     */
    public List<ShipmentItem> findItemsByShipmentIds(Collection<Long> shipmentIds) {
        if (shipmentIds.isEmpty()) {
            return List.of();
        }
        return queryFactory
                .selectFrom(shipmentItem)
                .where(shipmentItem.shipment.id.in(shipmentIds))
                .orderBy(shipmentItem.id.asc())
                .fetch();
    }
}
