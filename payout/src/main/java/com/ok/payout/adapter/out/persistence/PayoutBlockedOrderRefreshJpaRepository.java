package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutBlockedOrderRefreshRepository;
import com.ok.payout.domain.PayoutBlockedOrderRefresh;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 막힌 주문 집계 표 갱신 상태 저장소 출력 포트({@link PayoutBlockedOrderRefreshRepository})의 JPA 구현.
 */
public interface PayoutBlockedOrderRefreshJpaRepository
        extends JpaRepository<PayoutBlockedOrderRefresh, Long>, PayoutBlockedOrderRefreshRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    Optional<PayoutBlockedOrderRefresh> findById(Long id);
}
