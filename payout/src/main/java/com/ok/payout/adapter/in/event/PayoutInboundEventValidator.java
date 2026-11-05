package com.ok.payout.adapter.in.event;

import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import java.util.Optional;

/**
 * 정산이 다른 모듈에서 받은 이벤트 값을 입구에서 거르는 검사다.
 * 값이 틀린 이벤트는 다시 받아도 똑같이 틀리므로, 서비스(트랜잭션)를 부르기 전에 입구에서 거른다(#421).
 * Spring·Kafka 두 입구가 같은 기준을 쓰도록 한곳에 모았다. 걸렀을 때 어떻게 끝낼지는 부르는 입구가 정한다.
 * <p>
 * 입구마다 끝내는 방식이 다르다.
 * - Kafka 입구: 규칙(exception.md, "다른 모듈이 보낸 값의 오류는 표준 예외를 던진다")대로 IllegalArgumentException을 던진다.
 *   Kafka에는 실패 기록이 쌓이는 곳이 없어(정산 오류 처리기 {@code PayoutKafkaConsumerConfig}가 다시 시도하지 않고 ERROR 로그를 남긴 뒤 넘김),
 *   멘토 지적("재처리해도 안 되는 이벤트로 실패가 쌓이지 않게")도 함께 지킨다. 팀 DLT가 들어오면 이 예외로 원본이 DLT에 보관된다.
 * - Spring 이벤트 입구: 태그 로그를 남기고 정상 종료한다. 규칙과 다르다는 것을 알고 둔 구조다.
 *   여기서 예외를 던지면 실패 기록(event_publication)이 남고, 운영은 재시작 때 실패 건을 다시 보내므로 재시작마다 같은 이벤트가 또 실패한다.
 *   이 입구는 주문·지갑이 Kafka로 옮기면 통째로 지운다(#343). 그때 규칙과 다른 곳이 없어진다.
 * <p>
 * 걸러낸 이벤트는 정산 DB에 남지 않는다(Kafka는 오류 로그, Spring은 태그 로그).
 * 구매확정을 거르면 그 주문상품의 정산후보가 없어 판매자 정산에서 빠지고,
 * 지갑 차감을 거르면 배치 교차 검증에서 그 주문이 "차감 기록 없음"으로 걸린다. 이 위험을 알고 받아들였다.
 * 찾을 때는 태그({@code [PAYOUT_CANDIDATE_SKIPPED]}, {@code [PAYOUT_WALLET_DEDUCTION_SKIPPED]})로 로그를 검색하고,
 * 보낸 쪽이 원인을 고친 뒤 다시 발행한다. 정산만 따로 보관 테이블을 두지 않는 이유는, 원본 보관은 팀 DLT가 맡기 때문이다.
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
