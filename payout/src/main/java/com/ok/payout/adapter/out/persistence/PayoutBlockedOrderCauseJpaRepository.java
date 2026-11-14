package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutBlockedOrderCauseRepository;
import com.ok.payout.domain.PayoutBlockedOrderCause;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 막힌 주문 원인 저장소 출력 포트({@link PayoutBlockedOrderCauseRepository})의 JPA 구현.
 */
public interface PayoutBlockedOrderCauseJpaRepository
        extends JpaRepository<PayoutBlockedOrderCause, Long>, PayoutBlockedOrderCauseRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    <S extends PayoutBlockedOrderCause> List<S> saveAll(Iterable<S> causes);

    // 한 문장으로 지움 (파생 delete는 줄마다 조회 뒤 하나씩 지움)
    @Override
    @Modifying(flushAutomatically = true)
    @Query("delete from PayoutBlockedOrderCause c where c.orderId in :orderIds")
    void deleteAllByOrderIdIn(@Param("orderIds") Collection<Long> orderIds);

    // 테스트 검증용 (운영 코드에서는 사용하지 않음): 주문의 원인 확인
    List<PayoutBlockedOrderCause> findAllByOrderId(Long orderId);
}
