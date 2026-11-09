package com.ok.order.order.adapter.out.persistence;

import com.ok.order.order.application.port.out.OrderRepository;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 저장소 출력 포트의 JPA 구현. 쿼리 설정은 포트에 둘 수 없어(application은 영속 기술을 모른다) 여기서 다시 선언한다.
 */
public interface OrderJpaRepository extends JpaRepository<Order, Long>, OrderRepository {

    // JpaRepository와 포트가 같은 시그니처를 각각 물려줘 이 타입으로 호출하면 모호해지므로 한 번 더 선언한다
    @Override
    Optional<Order> findById(Long id);

    @Override
    void deleteById(Long id);

    @Override
    @Query("""
            select i from OrderItem i
            join fetch i.sellerGroup g
            join fetch g.order o
            where o.buyerId = :buyerId
              and i.productId = :productId
              and i.status = :status
            order by o.id desc, i.id asc
            """)
    List<OrderItem> findReviewCandidates(
            @Param("buyerId") Long buyerId,
            @Param("productId") Long productId,
            @Param("status") OrderItemStatus status
    );
    @Override
    @Query("""
            select distinct i.sellerGroup.order.id
            from OrderItem i
            where i.status = :status
              and i.deliveredAt <= :deadline
            """)
    List<Long> findOrderIdsToAutoConfirm(
            @Param("status") OrderItemStatus status,
            @Param("deadline") LocalDateTime deadline
    );
}
