package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.dto.OrderPaymentFailureReason;
import com.ok.wallet.application.dto.OrderPaymentResult;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import com.ok.wallet.application.port.out.OrderPaymentResultPublisher;
import com.ok.wallet.application.port.out.WalletEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 결제를 처리한다. 예치금을 차감하고 결과를 주문에 알린다.
 * 받은 길로만 답한다. Spring 이벤트로 받은 주문은 차감과 같은 트랜잭션에서 Spring 이벤트로 발행해, 차감이 커밋될 때만 완료가 나가게 한다.
 * Kafka로 받은 주문은 차감을 커밋한 뒤 Kafka로 보낸다. Spring 수신부는 자기 트랜잭션 안에서 이 서비스를 부르므로,
 * 거기서 Kafka로 보내면 커밋 전에 나가 롤백된 결제의 완료가 갈 수 있다.
 * 클래스에 @Transactional을 두지 않는다. 두면 Kafka 길도 트랜잭션 안에서 돌아 커밋 전에 보내게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService implements OrderPaymentUseCase {
    private final BuyerWalletService buyerWalletService;
    private final WalletEventPublisher walletEventPublisher;
    private final OrderPaymentResultPublisher orderPaymentResultPublisher;

    // 같은 주문의 동시 요청은 payForOrder가 처리 기록 넣기로 걸러내므로 격리 수준에 기대지 않는다(#360).
    @Override
    @Transactional
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
        // 처음 처리할 때 결과 이벤트를 보냈으므로 다시 보내지 않는다
        if (result.alreadyProcessed()) {
            log.info("이미 처리된 주문이라 결과 이벤트를 다시 보내지 않습니다. orderId={}, status={}",
                    result.orderId(), result.status());
            return;
        }
        if (result.paid()) {
            walletEventPublisher.publishOrderPaymentCompleted(
                    result.orderId(), result.buyerId(), result.amount(), result.processedAt());
            return;
        }
        // 처리 기록에 남는 실패는 잔액 부족뿐이다. 금액이 틀린 요청은 위에서 기록 없이 끝낸다
        walletEventPublisher.publishOrderPaymentFailed(
                result.orderId(), result.buyerId(), OrderPaymentFailureReason.INSUFFICIENT_BALANCE);
    }

    // 보내기가 실패하면 예외를 그대로 올려 같은 주문을 다시 받게 한다. 결제는 이미 기록돼 있어 다시 받으면 처음 결과를 다시 보낸다.
    // 그래서 먼저 기록해 두고 보내는 표(outbox)를 따로 두지 않는다(환불 반환과 같은 방식)
    @Override
    public void payAndSendResult(OrderPaymentCommand command) {
        if (command.orderId() == null || command.buyerId() == null) {
            throw new IllegalArgumentException("주문 ID와 구매자 ID는 필수입니다. command=" + command);
        }
        // 금액이 틀리면 기록 없이 실패를 보낸다. 다시 와도 결과가 같아 차감 없이 같은 실패가 다시 나간다
        if (command.amount() == null || command.amount() <= 0) {
            log.error("결제 금액이 올바르지 않아 결제하지 않습니다. command={}", command);
            orderPaymentResultPublisher.publishPaymentFailed(
                    command.orderId(), command.buyerId(), OrderPaymentFailureReason.INVALID_PAYMENT_REQUEST);
            return;
        }

        // 트랜잭션 없이 부르므로 payForOrder가 돌아오면 차감은 커밋돼 있다
        OrderPaymentResult result = buyerWalletService.payForOrder(
                command.buyerId(), command.orderId(), command.amount());
        if (result.alreadyProcessed()) {
            log.info("이미 처리한 주문이라 처음 결과를 다시 보냅니다. orderId={}, status={}, requestedAmount={}, recordedAmount={}",
                    result.orderId(), result.status(), command.amount(), result.amount());
        }
        if (result.paid()) {
            orderPaymentResultPublisher.publishPaymentCompleted(
                    result.orderId(), result.buyerId(), result.amount(), result.processedAt());
            return;
        }
        orderPaymentResultPublisher.publishPaymentFailed(
                result.orderId(), result.buyerId(), OrderPaymentFailureReason.INSUFFICIENT_BALANCE);
    }
}
