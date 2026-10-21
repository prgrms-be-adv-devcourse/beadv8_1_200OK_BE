package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderItem;
import com.ok.order.order.domain.OrderItemStatus;
import com.ok.order.order.domain.OrderSellerGroup;
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

import java.time.Clock;
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
    private final Clock clock;

    private static final int AUTO_CONFIRM_DAYS = 3;

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

    // 리뷰 작성 가능 구매 건 (상품 모듈 내부 API용)
    // 구매확정된 상품만: 확정 후에는 환불이 없어서 리뷰가 항상 실제 구매 건에 붙음
    public List<ReviewCandidateResult> getReviewCandidates(Long buyerId, Long productId) {
        List<OrderItem> items = orderRepository.findReviewCandidates(buyerId, productId, OrderItemStatus.PURCHASE_CONFIRMED);

        List<ReviewCandidateResult> results = new ArrayList<>();
        for (OrderItem item : items) {
            results.add(ReviewCandidateResult.from(item));
        }
        return results;
    }

    // 구매자 구매확정
    @Transactional
    public void confirmPurchase(Long buyerId, Long orderId, Long orderItemId) {
        // 내 주문이 아니면
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new RestApiException(OrderErrorCode.ORDER_NOT_FOUND));

        OrderItem item = order.findItem(orderItemId)
                .orElseThrow(() -> new RestApiException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));

        if (item.getStatus() == OrderItemStatus.PURCHASE_CONFIRMED) {
            throw new RestApiException(OrderErrorCode.ALREADY_PURCHASE_CONFIRMED);
        }
        if (!item.confirmPurchase(LocalDateTime.now(clock))) {
            throw new RestApiException(OrderErrorCode.PURCHASE_CONFIRM_NOT_ALLOWED);
        }

        publishConfirmed(order, item);
    }

    // 구매확정 이벤트 발행
    private void publishConfirmed(Order order, OrderItem item) {
        OrderSellerGroup group = item.getSellerGroup();
        orderEventPublisher.publishOrderProductConfirmedEvent(
                order.getId(),
                item.getId(),
                group.getSellerId(),
                item.getItemAmount(),     // TODO 부분 환불 정책이 정해지면 환불 수량 제외 (#28)
                group.getShippingFee()
        );
    }

    // 자동 구매확정 대상 주문 ID
    public List<Long> findOrderIdsToAutoConfirm() {
        return orderRepository.findOrderIdsToAutoConfirm(OrderItemStatus.DELIVERED, autoConfirmDeadline());
    }

    // 주문 하나의 자동 구매확정 (주문마다 트랜잭션을 나눠, 한 주문이 실패해도 다른 주문은 진행)
    @Transactional
    public int autoConfirmPurchases(Long orderId) {
        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty()) {
            log.warn("[ORDER_AUTO_CONFIRM_ORDER_NOT_FOUND] 자동 구매확정 대상 주문이 없습니다. orderId={}", orderId);
            return 0;
        }
        Order order = found.get();

        List<OrderItem> confirmed = order.confirmPurchasesDeliveredBefore(autoConfirmDeadline(), LocalDateTime.now(clock));
        for (OrderItem item : confirmed) {
            publishConfirmed(order, item);
        }
        return confirmed.size();
    }

    // 지금으로부터 3일 전
    private LocalDateTime autoConfirmDeadline() {
        return LocalDateTime.now(clock).minusDays(AUTO_CONFIRM_DAYS);
    }
}
