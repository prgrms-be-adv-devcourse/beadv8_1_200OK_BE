package com.ok.order.order.repository;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Page<Order> findByBuyerIdOrderByIdDesc(Long buyerId, Pageable pageable);
    Optional<Order> findByIdAndBuyerId(Long id, Long buyerId);
    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);
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
