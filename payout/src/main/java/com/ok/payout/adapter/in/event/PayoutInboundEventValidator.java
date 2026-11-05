package com.ok.payout.adapter.in.event;

import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import java.util.Optional;

/**
 * 정산이 다른 모듈에서 받은 이벤트 값을 입구에서 거르는 검사다.
 * 값이 틀린 이벤트는 다시 받아도 똑같이 틀리므로, 서비스(트랜잭션)를 부르기 전에 걸러
 * 로그만 남기고 정상 종료하기 위해 둔다(#421).
 * Spring·Kafka 두 입구가 같은 기준을 쓰도록 한곳에 모았다. 로그는 부르는 입구가 남긴다.
 */
final class PayoutInboundEventValidator {

    private PayoutInboundEventValidator() {
    }

    /**
     * 구매확정 값이 틀렸으면 먼저 걸린 이유 하나를 돌려준다. 0원은 정상이다(무료 상품·무료배송).
     */
    public static Optional<String> findInvalidReason(PurchaseConfirmedCommand command) {
        if (command.orderId() == null) {
            return Optional.of("orderId 없음");
        }
        if (command.orderItemId() == null) {
            return Optional.of("orderItemId 없음");
        }
        if (command.sellerId() == null) {
            return Optional.of("sellerId 없음");
        }
        if (command.totalItemPrice() == null) {
            return Optional.of("totalItemPrice 없음");
        }
        if (command.totalItemPrice() < 0) {
            return Optional.of("totalItemPrice가 음수");
        }
        if (command.sellerShippingPrice() == null) {
            return Optional.of("sellerShippingPrice 없음");
        }
        if (command.sellerShippingPrice() < 0) {
            return Optional.of("sellerShippingPrice가 음수");
        }
        return Optional.empty();
    }

    /**
     * 지갑 차감 값이 틀렸으면 먼저 걸린 이유 하나를 돌려준다. 0원은 정상이다.
     */
    public static Optional<String> findInvalidReason(WalletPaymentCompletedCommand command) {
        if (command.orderId() == null) {
            return Optional.of("orderId 없음");
        }
        if (command.buyerId() == null) {
            return Optional.of("buyerId 없음");
        }
        if (command.amount() == null) {
            return Optional.of("amount 없음");
        }
        if (command.paidAt() == null) {
            return Optional.of("paidAt 없음");
        }
        if (command.amount() < 0) {
            return Optional.of("amount가 음수");
        }
        return Optional.empty();
    }
}
