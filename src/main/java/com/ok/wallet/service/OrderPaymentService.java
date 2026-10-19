package com.ok.wallet.service;

import com.ok.wallet.service.dto.OrderPaymentCommand;
import com.ok.wallet.service.dto.OrderPaymentFailureReason;
import com.ok.wallet.service.dto.OrderPaymentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 주문 결제를 처리한다. 예치금을 차감하고 결과를 주문에 이벤트로 알린다.
 * 결과 이벤트는 차감과 같은 트랜잭션에서 발행해, 차감이 커밋될 때만 성공 이벤트가 나가게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService {
    private final BuyerWalletService buyerWalletService;
    private final WalletEventPublisher walletEventPublisher;
    private final Clock clock;

    /**
     * 주문 결제 금액을 차감하고 결과 이벤트를 발행한다.
     * payForOrder가 이 트랜잭션에 합류하므로, 같은 주문의 동시 요청을 최신으로 보려면 여기서도 READ COMMITTED여야 한다
     * (격리 수준은 트랜잭션을 새로 시작할 때만 적용된다).
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void pay(OrderPaymentCommand command) {
        // 실패를 알릴 대상(주문·구매자)이 없으면 이벤트를 보낼 수 없다. 재처리해도 같으므로 기록만 하고 끝낸다
        if (command.orderId() == null || command.buyerId() == null) {
            log.error("[ORDER_PAYMENT_SKIPPED] 주문 또는 구매자 ID가 없어 결제를 처리할 수 없습니다. command={}", command);
            return;
        }
        // 결제를 기다리는 주문이 멈추지 않도록, 처리할 수 없는 금액은 실패 이벤트로 알린다
        if (command.amount() == null || command.amount() <= 0) {
            log.error("결제 금액이 올바르지 않아 결제하지 않습니다. command={}", command);
            walletEventPublisher.publishOrderPaymentFailed(
                    command.orderId(), command.buyerId(), OrderPaymentFailureReason.INVALID_PAYMENT_REQUEST);
            return;
        }

        OrderPaymentResult result = buyerWalletService.payForOrder(
                command.buyerId(), command.orderId(), command.amount());
        switch (result) {
            case PAID -> walletEventPublisher.publishOrderPaymentCompleted(
                    command.orderId(), command.buyerId(), command.amount(), LocalDateTime.now(clock));
            case INSUFFICIENT_BALANCE -> walletEventPublisher.publishOrderPaymentFailed(
                    command.orderId(), command.buyerId(), OrderPaymentFailureReason.INSUFFICIENT_BALANCE);
            // 이미 차감된 주문은 그때 성공 이벤트를 보냈으므로 다시 보내지 않는다
            case ALREADY_PAID -> log.info("이미 결제된 주문이라 결과 이벤트를 다시 보내지 않습니다. orderId={}", command.orderId());
        }
    }
}
