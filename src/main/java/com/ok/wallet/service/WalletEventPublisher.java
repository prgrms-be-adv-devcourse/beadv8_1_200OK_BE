package com.ok.wallet.service;

import com.ok.wallet.service.dto.OrderPaymentFailureReason;

import java.time.LocalDateTime;

/**
 * 지갑 모듈의 이벤트 발행 인터페이스. 서비스는 이 인터페이스로만 이벤트를 발행한다.
 *
 * <p>발행 방식(스프링 이벤트, 메시지 브로커 등)은 구현체({@code event} 패키지)가 맡아, 방식을 바꿔도 서비스는 바뀌지 않는다.
 * 이벤트 클래스는 구현체 안에서만 만들도록 값을 받는다. (컨벤션: structure.md)
 */
public interface WalletEventPublisher {

    /** 주문 결제 금액을 예치금에서 차감했음을 알린다. 주문은 이 이벤트로 결제 완료를 처리한다. */
    void publishOrderPaymentCompleted(Long orderId, Long buyerId, long amount, LocalDateTime paidAt);

    /** 주문 결제 금액을 차감하지 못했음을 알린다. 주문은 이 이벤트로 결제 실패를 처리하고 재고를 푼다. */
    void publishOrderPaymentFailed(Long orderId, Long buyerId, OrderPaymentFailureReason reason);
}
