package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.dto.OrderDetailResult;
import com.ok.order.order.service.dto.OrderSummaryResult;
import com.ok.order.order.service.dto.ReviewCandidateResult;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
        Order order = findOrder(orderId);

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
        Order order = findOrder(orderId);

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

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new RestApiException(OrderErrorCode.ORDER_NOT_FOUND));
    }

    private List<Long> orderedOptionIds(Order order) {
        List<Long> optionIds = new ArrayList<>();
        for (OrderItem item : order.getAllItems()) {
            optionIds.add(item.getProductOptionId());
        }
        return optionIds;
    }

    // 리뷰 작성 가능 구매 건 (상품 모듈 내부 API용)
    public List<ReviewCandidateResult> getReviewCandidates(Long buyerId, Long productId) {
        List<OrderItem> items = orderRepository.findReviewCandidates(buyerId, productId, OrderItemStatus.PURCHASE_CONFIRMED);

        List<ReviewCandidateResult> results = new ArrayList<>();
        for (OrderItem item : items) {
            results.add(ReviewCandidateResult.from(item));
        }
        return results;
    }
}

