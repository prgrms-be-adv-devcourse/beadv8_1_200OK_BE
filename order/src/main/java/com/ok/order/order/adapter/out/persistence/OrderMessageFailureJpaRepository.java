package com.ok.order.order.adapter.out.persistence;

import com.ok.order.order.application.port.out.OrderMessageFailureRepository;
import com.ok.order.order.domain.OrderMessageFailure;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 주문 Kafka 실패 기록 저장소 출력 포트의 JPA 구현.
 */
public interface OrderMessageFailureJpaRepository
        extends JpaRepository<OrderMessageFailure, Long>, OrderMessageFailureRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    OrderMessageFailure save(OrderMessageFailure failure);
}
