package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.OrderPaymentCommand;
import com.ok.wallet.application.dto.OrderPaymentExpireCommand;
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
 * 주문 결제와 결제 만료 요청을 처리한다. 결과(결제됨·실패·만료)를 처리 기록에 남기고, 커밋한 뒤 결과를 Kafka로 주문·정산에 보낸다.
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
            logAlreadyProcessed(command.buyerId(), command.amount(), processed.get());
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
            logAlreadyProcessed(command.buyerId(), command.amount(), result);
        }
        sendResult(result);
    }

    // 결과를 정하는 것은 지갑이다. 주문은 결과가 올 때까지 만료 요청을 다시 보내고, 지갑은 올 때마다 처음 결과를 보낸다
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public void expireAndSendResult(OrderPaymentExpireCommand command) {
        if (command.orderId() == null || command.buyerId() == null || command.amount() == null || command.amount() <= 0) {
            throw new IllegalArgumentException("주문 ID, 구매자 ID, 0보다 큰 금액은 필수입니다. command=" + command);
        }
        // 트랜잭션 없이 부르므로 expireOrderPayment가 돌아오면 기록은 커밋돼 있다
        OrderPaymentResult result = buyerWalletService.expireOrderPayment(
                command.buyerId(), command.orderId(), command.amount());
        if (result.alreadyProcessed()) {
            logAlreadyProcessed(command.buyerId(), command.amount(), result);
        } else {
            log.info("결제 요청보다 만료 요청이 먼저 와 결제 만료로 기록했습니다. orderId={}, buyerId={}",
                    result.orderId(), result.buyerId());
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
    private static void logAlreadyProcessed(Long requestedBuyerId, Long requestedAmount, OrderPaymentResult result) {
        // 금액이 없으면 처음과 다른 요청으로 본다
        if (requestedAmount != null && result.sameRequest(requestedBuyerId, requestedAmount)) {
            log.info("이미 처리한 주문입니다. 처음 결과를 다시 보냅니다. orderId={}, status={}", result.orderId(), result.status());
            return;
        }
        log.warn("[ORDER_PAYMENT_MISMATCH] 이미 처리한 주문이 처음과 다른 값으로 왔습니다. 처음 결과를 다시 보냅니다. orderId={}, status={}, "
                        + "requestedBuyerId={}, recordedBuyerId={}, requestedAmount={}, recordedAmount={}",
                result.orderId(), result.status(),
                requestedBuyerId, result.buyerId(), requestedAmount, result.amount());
    }
}
