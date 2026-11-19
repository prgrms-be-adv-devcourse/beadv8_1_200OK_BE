package com.ok.wallet.application.service;

import com.ok.wallet.application.dto.OrderRefundRevertCommand;
import com.ok.wallet.application.dto.OrderRefundRevertResult;
import com.ok.wallet.application.port.in.OrderRefundRevertUseCase;
import com.ok.wallet.application.port.out.WalletRefundResultPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 환불 되돌리기 요청을 처리하고 결과를 환불에 알린다. 이 클래스에는 @Transactional을 두지 않는다.
 * 결과를 커밋 뒤에 보내고, 보내기가 실패하면 예외를 올려 다시 받게 하는 이유는 {@link OrderRefundService}와 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderRefundRevertService implements OrderRefundRevertUseCase {
    private final BuyerWalletService buyerWalletService;
    private final WalletRefundResultPublisher walletRefundResultPublisher;

    @Override
    public void revert(OrderRefundRevertCommand command) {
        // 금액이 비면 아래에서 long으로 바꾸다 NullPointerException이 나 원인이 흐려진다. 다른 값 검사는 revertRefundForOrder가 한다
        if (command.amount() == null) {
            throw new IllegalArgumentException("되돌릴 금액은 필수입니다. command=" + command);
        }
        OrderRefundRevertResult result = buyerWalletService.revertRefundForOrder(
                command.refundId(), command.orderId(), command.buyerId(), command.amount());
        if (result.alreadyProcessed()) {
            log.info("이미 처리한 되돌리기라 처음 결과를 다시 보냅니다. refundId={}, status={}", result.refundId(), result.status());
        }
        // 결과에는 지갑의 반환 기록 값이 나간다. 요청 값과 다르면 환불 쪽 기록이 어긋난 것이라 사람이 찾을 수 있게 남긴다
        if (!Objects.equals(command.orderId(), result.orderId()) || !Objects.equals(command.buyerId(), result.buyerId())
                || command.amount() != result.amount()) {
            log.warn("되돌리기 요청 값이 지갑의 반환 기록과 다릅니다. refundId={}, requestedOrderId={}, requestedBuyerId={}, "
                            + "requestedAmount={}, recordedOrderId={}, recordedBuyerId={}, recordedAmount={}",
                    result.refundId(), command.orderId(), command.buyerId(), command.amount(),
                    result.orderId(), result.buyerId(), result.amount());
        }
        if (result.reverted()) {
            walletRefundResultPublisher.publishRefundReverted(
                    result.refundId(), result.orderId(), result.buyerId(), result.amount());
            return;
        }
        // 가져오지 못한 돈은 구매자에게 남아 사람이 봐야 하므로 남긴다
        log.warn("환불 되돌리기를 하지 못했습니다. refundId={}, orderId={}, buyerId={}, amount={}, reason={}",
                result.refundId(), result.orderId(), result.buyerId(), result.amount(), result.failureReason());
        walletRefundResultPublisher.publishRefundRevertFailed(
                result.refundId(), result.orderId(), result.buyerId(), result.amount(), result.failureReason());
    }
}
