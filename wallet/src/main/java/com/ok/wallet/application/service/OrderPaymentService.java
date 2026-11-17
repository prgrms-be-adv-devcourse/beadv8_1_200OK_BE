package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.dto.OrderPaymentFailureReason;
import com.ok.wallet.application.dto.OrderPaymentResult;
import com.ok.wallet.application.port.in.OrderPaymentUseCase;
import com.ok.wallet.application.port.out.OrderPaymentResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 주문 결제를 처리한다. 예치금을 차감하고, 차감을 커밋한 뒤 결과를 Kafka로 주문·정산에 보낸다.
 * 클래스에 @Transactional을 두지 않는다. 두면 커밋 전에 보내, 롤백된 결제의 완료가 나갈 수 있다. 메서드를 더하면 트랜잭션을 메서드마다 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaymentService implements OrderPaymentUseCase {
    private final BuyerWalletService buyerWalletService;
    private final OrderPaymentResultPublisher orderPaymentResultPublisher;

    // 보내기가 실패하면 예외를 그대로 올려 같은 주문을 다시 받게 한다. 결제는 이미 기록돼 있어 다시 받으면 처음 결과를 다시 보낸다.
    // 그래서 먼저 기록해 두고 보내는 표(outbox)를 따로 두지 않는다(환불 반환과 같은 방식)
    // NEVER: 트랜잭션 안에서 부르면 바로 예외로 막는다. 안에서 돌면 커밋 전에 보내, 롤백된 결제의 완료가 나갈 수 있다
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void payAndSendResult(OrderPaymentCommand command) {
        if (command.orderId() == null || command.buyerId() == null) {
            throw new IllegalArgumentException("주문 ID와 구매자 ID는 필수입니다. command=" + command);
        }
        // 이미 처리한 주문이면 금액을 보기 전에 처음 결과를 다시 보낸다. 다른 금액으로 다시 와도 처음 결과가 기준이다
        Optional<OrderPaymentResult> processed = buyerWalletService.findProcessedOrderPayment(command.orderId());
        if (processed.isPresent()) {
            logAlreadyProcessed(command, processed.get(), "처음 결과를 다시 보냅니다.");
            sendResult(processed.get());
            return;
        }
        // 처음 보는 주문의 금액이 틀리면 기록 없이 실패를 보낸다. 다시 와도 결과가 같아 차감 없이 같은 실패가 다시 나간다
        if (command.amount() == null || command.amount() <= 0) {
            log.error("결제 금액이 올바르지 않아 결제하지 않습니다. command={}", command);
            orderPaymentResultPublisher.publishPaymentFailed(
                    command.orderId(), command.buyerId(), OrderPaymentFailureReason.INVALID_PAYMENT_REQUEST);
            return;
        }

        // 트랜잭션 없이 부르므로 payForOrder가 돌아오면 차감은 커밋돼 있다
        OrderPaymentResult result = buyerWalletService.payForOrder(
                command.buyerId(), command.orderId(), command.amount());
        // 위에서 기록이 없었어도 같은 주문이 동시에 와서 먼저 처리됐을 수 있다. 그때는 처리 기록 넣기가 걸러 처음 결과가 온다
        if (result.alreadyProcessed()) {
            logAlreadyProcessed(command, result, "처음 결과를 다시 보냅니다.");
        }
        sendResult(result);
    }

    private void sendResult(OrderPaymentResult result) {
        if (result.paid()) {
            orderPaymentResultPublisher.publishPaymentCompleted(
                    result.orderId(), result.buyerId(), result.amount(), result.processedAt());
            return;
        }
        orderPaymentResultPublisher.publishPaymentFailed(result.orderId(), result.buyerId(), result.failureReason());
    }

    // 같은 주문이 처음과 다른 구매자·금액으로 오면 주문 쪽 이상일 수 있어 사람이 보도록 WARN으로 남긴다.
    // 어느 쪽이든 결과는 처음 기록이 기준이다
    private static void logAlreadyProcessed(OrderPaymentCommand command, OrderPaymentResult result, String action) {
        // 금액이 없으면 처음과 다른 요청으로 본다
        if (command.amount() != null && result.sameRequest(command.buyerId(), command.amount())) {
            log.info("이미 처리한 주문입니다. {} orderId={}, status={}", action, result.orderId(), result.status());
            return;
        }
        log.warn("[ORDER_PAYMENT_MISMATCH] 이미 처리한 주문이 처음과 다른 값으로 왔습니다. {} orderId={}, status={}, "
                        + "requestedBuyerId={}, recordedBuyerId={}, requestedAmount={}, recordedAmount={}",
                action, result.orderId(), result.status(),
                command.buyerId(), result.buyerId(), command.amount(), result.amount());
    }
}
