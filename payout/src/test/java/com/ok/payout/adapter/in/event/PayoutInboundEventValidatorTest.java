package com.ok.payout.adapter.in.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import com.ok.payout.application.port.in.WalletPaymentCompletedCommand;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PayoutInboundEventValidatorTest {

    private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 11, 5, 10, 0);

    @ParameterizedTest(name = "{0}")
    @MethodSource("구매확정_틀린_값")
    void 구매확정_값이_틀리면_이유를_돌려준다(String 경우, PurchaseConfirmedCommand sut, String 기대_이유) {
        // given: 인자로 받은 틀린 커맨드

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).contains(기대_이유);
    }

    static Stream<Arguments> 구매확정_틀린_값() {
        return Stream.of(
                Arguments.of("orderId 없음", new PurchaseConfirmedCommand(null, 2L, 3L, 1000L, 0L), "orderId 없음"),
                Arguments.of("orderItemId 없음", new PurchaseConfirmedCommand(1L, null, 3L, 1000L, 0L), "orderItemId 없음"),
                Arguments.of("sellerId 없음", new PurchaseConfirmedCommand(1L, 2L, null, 1000L, 0L), "sellerId 없음"),
                Arguments.of("totalItemPrice 없음", new PurchaseConfirmedCommand(1L, 2L, 3L, null, 0L), "totalItemPrice 없음"),
                Arguments.of("totalItemPrice 음수", new PurchaseConfirmedCommand(1L, 2L, 3L, -1L, 0L), "totalItemPrice가 음수"),
                Arguments.of("sellerShippingPrice 없음", new PurchaseConfirmedCommand(1L, 2L, 3L, 1000L, null), "sellerShippingPrice 없음"),
                Arguments.of("sellerShippingPrice 음수", new PurchaseConfirmedCommand(1L, 2L, 3L, 1000L, -1L), "sellerShippingPrice가 음수"),
                // 사고 3: 여러 값이 틀려도 먼저 검사하는 이유 하나만
                Arguments.of("여러 값 틀림", new PurchaseConfirmedCommand(null, null, null, -1L, -1L), "orderId 없음"),
                Arguments.of("금액만 여러 개 틀림", new PurchaseConfirmedCommand(1L, 2L, 3L, -1L, null), "totalItemPrice가 음수")
        );
    }

    @Test
    void 구매확정_금액이_0원이어도_통과한다() {
        // given: 사고 1, 무료 상품에 무료배송
        PurchaseConfirmedCommand sut = new PurchaseConfirmedCommand(1L, 2L, 3L, 0L, 0L);

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).isEmpty();
    }

    @Test
    void 구매확정_값이_모두_맞으면_비어_있다() {
        // given
        PurchaseConfirmedCommand sut = new PurchaseConfirmedCommand(1L, 2L, 3L, 10000L, 3000L);

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("지갑_차감_틀린_값")
    void 지갑_차감_값이_틀리면_이유를_돌려준다(String 경우, WalletPaymentCompletedCommand sut, String 기대_이유) {
        // given: 인자로 받은 틀린 커맨드

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).contains(기대_이유);
    }

    static Stream<Arguments> 지갑_차감_틀린_값() {
        return Stream.of(
                Arguments.of("orderId 없음", new WalletPaymentCompletedCommand(null, 2L, 1000L, PAID_AT), "orderId 없음"),
                Arguments.of("buyerId 없음", new WalletPaymentCompletedCommand(1L, null, 1000L, PAID_AT), "buyerId 없음"),
                Arguments.of("amount 없음", new WalletPaymentCompletedCommand(1L, 2L, null, PAID_AT), "amount 없음"),
                Arguments.of("paidAt 없음", new WalletPaymentCompletedCommand(1L, 2L, 1000L, null), "paidAt 없음"),
                Arguments.of("amount 음수", new WalletPaymentCompletedCommand(1L, 2L, -1L, PAID_AT), "amount가 음수"),
                // 사고 3: 여러 값이 틀려도 먼저 검사하는 이유 하나만
                Arguments.of("여러 값 틀림", new WalletPaymentCompletedCommand(null, null, -1L, null), "orderId 없음"),
                Arguments.of("paidAt 없음과 amount 음수", new WalletPaymentCompletedCommand(1L, 2L, -1L, null), "paidAt 없음")
        );
    }

    @Test
    void 지갑_차감_금액이_0원이어도_통과한다() {
        // given: 사고 2
        WalletPaymentCompletedCommand sut = new WalletPaymentCompletedCommand(1L, 2L, 0L, PAID_AT);

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).isEmpty();
    }

    @Test
    void 지갑_차감_값이_모두_맞으면_비어_있다() {
        // given
        WalletPaymentCompletedCommand sut = new WalletPaymentCompletedCommand(1L, 2L, 5000L, PAID_AT);

        // when
        Optional<String> reason = PayoutInboundEventValidator.findInvalidReason(sut);

        // then
        assertThat(reason).isEmpty();
    }
}
