package com.ok.payout.adapter.in.event;

import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import java.util.Optional;

/**
 * 정산이 다른 모듈에서 받은 이벤트 값을 입구에서 거르는 검사다.
 * 값이 틀린 이벤트는 다시 받아도 똑같이 틀리므로, 서비스(트랜잭션)를 부르기 전에 걸러
 * 로그만 남기고 정상 종료하기 위해 둔다(#421).
 * Spring·Kafka 두 입구가 같은 기준을 쓰도록 한곳에 모았다. 로그는 부르는 입구가 남긴다.
 * <p>
 * 걸러낸 이벤트는 태그 로그({@code [PAYOUT_CANDIDATE_SKIPPED]}, {@code [PAYOUT_WALLET_DEDUCTION_SKIPPED]})로만 남고 DB에는 남지 않는다.
 * 실패 메시지 보관(DLT·테이블)과 다시 처리는 outbox 등 팀 공통 방식이 생길 때 그 방식으로 붙인다(이슈 #421 범위).
 * 정산만 따로 보관 테이블을 만들면 공통 방식이 생긴 뒤 두 곳에 나뉘어 남기 때문에 지금은 만들지 않는다.
 * <p>
 * 팀 규칙과 다르다는 것을 알고 둔 구조다. 규칙(exception.md, "다른 모듈이 보낸 값의 오류는 표준 예외를 던진다")대로라면
 * 여기서 IllegalArgumentException을 던져야 하지만, 멘토 리뷰("재처리해도 안 되는 이벤트는 기록 후 정상 종료")를 반영하다
 * 규칙과 어긋나는 것을 발견했다. 규칙대로 던지면 Spring 이벤트 입구에서는 실패 기록이 쌓이는데,
 * 이 입구는 주문·지갑이 Kafka로 옮기면 지울 예정(#343)이라 지금 구조로 남겨 둔다.
 * TODO(#343): Spring 입구를 지울 때 Kafka 입구도 규칙대로 IllegalArgumentException을 던지게 바꾼다.
 *   Kafka는 예외를 던져도 몇 번 다시 시도한 뒤 넘어갈 뿐 실패 기록이 쌓이지 않아,
 *   그때는 멘토 지적(쌓이지 않게)과 규칙(예외를 던진다)을 둘 다 지킬 수 있다.
 */
final class PayoutInboundEventValidator {

    private PayoutInboundEventValidator() {
    }

    /**
     * 구매확정 값이 틀렸으면 먼저 걸린 이유 하나를 돌려준다. 0원은 정상이다(무료 상품·무료배송).
     */
    static Optional<String> findInvalidReason(PurchaseConfirmedCommand command) {
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
    static Optional<String> findInvalidReason(WalletPaymentCompletedCommand command) {
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
