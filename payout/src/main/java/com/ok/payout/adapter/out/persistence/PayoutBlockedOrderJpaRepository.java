package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutBlockedOrderRepository;
import com.ok.payout.domain.PayoutBlockedOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 막힌 주문 집계 표 저장소 출력 포트({@link PayoutBlockedOrderRepository})의 JPA 구현.
 */
public interface PayoutBlockedOrderJpaRepository
        extends JpaRepository<PayoutBlockedOrder, Long>, PayoutBlockedOrderRepository {

    @Override
    @Query("select b.orderId from PayoutBlockedOrder b")
    List<Long> findAllOrderIds();

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutBlockedOrder> List<S> saveAll(Iterable<S> blockedOrders);

    // 한 문장으로 지움 (파생 delete는 줄마다 조회 뒤 하나씩 지워 막힌 주문 수만큼 문장이 나감)
    @Override
    @Modifying(flushAutomatically = true)
    @Query("delete from PayoutBlockedOrder b where b.orderId in :orderIds")
    void deleteAllByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);
}
