package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.dto.OrderDetailResult;
import com.ok.order.order.service.dto.OrderSummaryResult;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final CartService cartService;

    // 주문 저장
    @Transactional
    public Long create(Order order) {
        return orderRepository.save(order).getId();
    }

    // 주문 생성 이벤트 발행
    @Transactional
    public void publishCreated(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RestApiException(OrderErrorCode.ORDER_NOT_FOUND));
        orderEventPublisher.publishOrderCreatedEvent(order.getId(), order.getBuyerId(), order.getTotalAmount());
    }

    // 보상용 삭제
    @Transactional
    public void delete(Long orderId) {
        orderRepository.deleteById(orderId);
    }

    // 내 주문 목록 (최신순)
    public Page<OrderSummaryResult> getOrders(Long buyerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return orderRepository.findByBuyerIdOrderByIdDesc(buyerId, pageRequest)
                .map(OrderSummaryResult::from);
    }

    // 내 주문 상세
    public OrderDetailResult getOrder(Long buyerId, Long orderId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new RestApiException(OrderErrorCode.ORDER_NOT_FOUND));
        return OrderDetailResult.from(order);
    }

    // 결제 완료
    @Transactional
    public void completePayment(Long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.error("[ORDER_PAYMENT_ORDER_NOT_FOUND] 결제 완료 대상 주문이 없습니다. orderId={}", orderId);
            return;
        }
        Order order = found.get();

        if (!order.isPaymentPending()) {
            if (order.isPaymentFailed()) {
                // 실패 처리 후 완료가 온 경우: 돈은 빠졌는데 주문은 실패 → 환불 확인 필요
                log.error("[ORDER_PAID_AFTER_FAILED] 결제 실패 주문에 결제 완료가 도착했습니다. 환불 확인이 필요합니다. orderId={}", orderId);
            } else {
                log.info("[ORDER_PAYMENT_ALREADY_HANDLED] 이미 처리된 주문입니다. orderId={}, status={}", orderId, order.getStatus());
            }
            return;
        }

        order.markPaid();
        cartService.removeOrderItems(order.getBuyerId(), orderedOptionIds(order));
    }

    @Transactional
    public boolean failPayment(Long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.error("[ORDER_PAYMENT_ORDER_NOT_FOUND] 결제 실패 대상 주문이 없습니다. orderId={}", orderId);
            return false;
        }
        Order order = found.get();

        if (order.isPaymentPending()) {
            order.markPaymentFailed();
            return true;
        }

        if (order.isPaymentFailed()) {
            log.info("[ORDER_PAYMENT_FAILED_AGAIN] 이미 결제 실패 처리된 주문입니다. 재고 해제만 다시 시도합니다. orderId={}", orderId);
            return true;
        }

        // 결제 완료 등 다른 상태 → 무시
        log.warn("[ORDER_PAYMENT_FAILED_IGNORED] 결제 대기가 아닌 주문에 결제 실패가 도착했습니다. orderId={}, status={}", orderId, order.getStatus());
        return false;
    }

    private List<Long> orderedOptionIds(Order order) {
        List<Long> optionIds = new ArrayList<>();
        for (OrderItem item : order.getAllItems()) {
            optionIds.add(item.getProductOptionId());
        }
        return optionIds;
    }

    // 배송 시작: 해당 판매자 묶음의 배송 준비 상품을 배송 중으로
    @Transactional
    public void startShipping(Long orderId, Long sellerId) {
        Optional<Order> order = orderRepository.findById(orderId);
        if (order.isEmpty()) {
            // 예외를 던지면 이벤트가 계속 재처리되므로 로그만 남김
            log.warn("[ORDER_SHIPPING_ORDER_NOT_FOUND] 배송 시작 대상 주문이 없습니다. orderId={}, sellerId={}", orderId, sellerId);
            return;
        }

        int changed = order.get().markShipping(sellerId);
        if (changed == 0) {
            log.info("[ORDER_SHIPPING_IGNORED] 배송 중으로 바꿀 상품이 없습니다. orderId={}, sellerId={}", orderId, sellerId);
        }
    }

    // 배송 완료: 해당 판매자 묶음의 상품을 배송 완료로 + 배송 완료 시각 기록
    @Transactional
    public void completeDelivery(Long orderId, Long sellerId, LocalDateTime deliveredAt) {
        Optional<Order> order = orderRepository.findById(orderId);
        if (order.isEmpty()) {
            log.warn("[ORDER_DELIVERY_ORDER_NOT_FOUND] 배송 완료 대상 주문이 없습니다. orderId={}, sellerId={}", orderId, sellerId);
            return;
        }

        int changed = order.get().markDelivered(sellerId, deliveredAt);
        if (changed == 0) {
            log.info("[ORDER_DELIVERY_IGNORED] 배송 완료로 바꿀 상품이 없습니다. orderId={}, sellerId={}", orderId, sellerId);
        }
    }
}
