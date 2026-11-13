package com.ok.order.order.adapter.out.persistence;

import com.ok.order.order.application.port.out.OrderRepository;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 포트는 JPA를 몰라 @Lock을 붙일 수 없다. 여기서 다시 선언하지 않으면 잠금 없는 조회로 오류 없이 돈다
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    // 하위 쿼리의 주문 상품·판매자 묶음 행은 잠그지 않고 바깥의 주문 행만 잠근다(MySQL 잠금 읽기는 하위 쿼리에 번지지 않는다)
    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from Order o
            where o.id = (select i.sellerGroup.order.id from OrderItem i where i.id = :orderItemId)
            """)
    Optional<Order> findByOrderItemIdForUpdate(@Param("orderItemId") Long orderItemId);

    @Override
    @Query("select r.orderItem.id from OrderItemRefund r where r.refundId = :refundId")
    Optional<Long> findOrderItemIdByRefundId(@Param("refundId") Long refundId);

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
