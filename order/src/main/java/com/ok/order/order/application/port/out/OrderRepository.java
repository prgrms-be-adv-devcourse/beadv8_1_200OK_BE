package com.ok.order.order.application.port.out;

import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long id);

    void deleteById(Long id);

    Page<Order> findByBuyerIdOrderByIdDesc(Long buyerId, Pageable pageable);

    Optional<Order> findByIdAndBuyerId(Long id, Long buyerId);

    Optional<Order> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);

    /**
     * 구매자가 산 상품 중 리뷰를 쓸 수 있는 상태의 주문 상품을 최신 주문부터 조회한다. 판매자 묶음과 주문을 함께 읽는다.
     */
    List<OrderItem> findReviewCandidates(Long buyerId, Long productId, OrderItemStatus status);

    /**
     * 기준 시각 이전에 배송 완료된 상품이 있는 주문 ID를 조회한다 (자동 구매확정 대상).
     */
    List<Long> findOrderIdsToAutoConfirm(OrderItemStatus status, LocalDateTime deadline);
}
