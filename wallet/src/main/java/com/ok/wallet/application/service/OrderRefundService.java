package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.OrderRefundCommand;
import com.ok.wallet.application.dto.OrderRefundResult;
import com.ok.wallet.application.port.in.OrderRefundUseCase;
import com.ok.wallet.application.port.out.WalletRefundResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 환불 반환 요청을 처리하고 결과를 환불에 알린다. 이 클래스에는 @Transactional을 두지 않는다.
 * 결과는 반환이 커밋된 뒤에 보내야 한다. 커밋 전에 보내면 커밋이 실패했을 때 돌려주지 않은 돈을 돌려줬다고 알리게 된다.
 * 보내기가 실패하면 예외를 그대로 올려 같은 요청을 다시 받게 한다. 반환은 이미 기록돼 있어 다시 받으면 돈은 다시 주지 않고
 * 처음 결과를 다시 보낸다. 그래서 먼저 기록해 두고 보내는 표(outbox)를 따로 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundService implements OrderRefundUseCase {
    private final BuyerWalletService buyerWalletService;
    private final WalletRefundResultPublisher walletRefundResultPublisher;

    @Override
    public void refund(OrderRefundCommand command) {
        // 금액이 비면 아래에서 long으로 바꾸다 NullPointerException이 나 원인이 흐려진다. 다른 값 검사는 refundForOrder가 한다
        if (command.amount() == null) {
            throw new IllegalArgumentException("반환 금액은 필수입니다. command=" + command);
        }
        OrderRefundResult result = buyerWalletService.refundForOrder(
                command.refundId(), command.orderId(), command.buyerId(), command.amount());
        if (result.alreadyProcessed()) {
            log.info("이미 처리한 환불이라 처음 결과를 다시 보냅니다. refundId={}, status={}, requestedAmount={}, recordedAmount={}",
                    result.refundId(), result.status(), command.amount(), result.amount());
        }
        if (result.completed()) {
            walletRefundResultPublisher.publishRefundCompleted(
                    result.refundId(), result.orderId(), result.buyerId(), result.amount());
            return;
        }
        // 거절은 다시 해도 결과가 같은 경우만이라, 환불이 주문 취소를 되돌리는 근거가 된다. 사람이 볼 수 있게 남긴다
        log.warn("환불 반환을 거절했습니다. refundId={}, orderId={}, buyerId={}, amount={}, reason={}",
                result.refundId(), result.orderId(), result.buyerId(), result.amount(), result.failureReason());
        walletRefundResultPublisher.publishRefundFailed(
                result.refundId(), result.orderId(), result.buyerId(), result.amount(), result.failureReason());
    }
}
