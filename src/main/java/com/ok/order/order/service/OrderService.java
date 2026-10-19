package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.order.domain.Order;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.repository.OrderRepository;
import com.ok.order.order.service.publisher.OrderEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventPublisher orderEventPublisher;

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
}
