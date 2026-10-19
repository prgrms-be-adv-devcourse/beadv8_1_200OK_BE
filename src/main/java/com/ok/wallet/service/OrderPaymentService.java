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
 * 결과 이벤트는 차감과 같은 트랜잭션에서 발행해, 차감이 커밋될 때만 완료 이벤트가 나가게 한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
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
        if (command.orderId() == null || command.buyerId() == null) {
            // 결과 이벤트는 주문 ID와 구매자 ID가 모두 필수라(이벤트 생성 시 검증), 둘 중 하나라도 없으면 실패도 알릴 수 없다.
            // 주문 생성 이벤트도 두 값을 필수로 검증하므로 정상 흐름에서는 오지 않는다.
            // 결과를 알릴 주문을 알 수 없어 사람이 확인해야 하므로 알림 대상이 되도록 ERROR로 남기고, 다시 처리해도 같아 정상 종료한다.
            log.error("[ORDER_PAYMENT_SKIPPED] 주문 또는 구매자 ID가 없어 결제를 처리할 수 없습니다. command={}", command);
            return;
        }
        // 결제를 기다리는 주문이 멈추지 않도록, 처리할 수 없는 금액은 실패 이벤트로 알린다.
        // 이 경우는 처리 기록을 남기지 않아, 같은 주문이 재전달되면 실패 이벤트가 다시 나간다(결과는 항상 같아 차감은 없다).
        // 잔액 부족은 기록을 남겨 재전달 시 다시 보내지 않는 것과 다르므로, 주문은 실패 이벤트 중복 수신을 무시해야 한다.
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
            // 처음 처리할 때 결과 이벤트를 보냈으므로 다시 보내지 않는다
            case ALREADY_PAID, ALREADY_FAILED -> log.info(
                    "이미 처리된 주문이라 결과 이벤트를 다시 보내지 않습니다. orderId={}, result={}",
                    command.orderId(), result);
        }
    }
}
