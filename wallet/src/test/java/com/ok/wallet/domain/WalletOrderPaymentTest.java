package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class WalletOrderPaymentTest {

    @ParameterizedTest
    @MethodSource("빈_주문_요약")
    void 주문_요약이_비었으면_null로_저장한다(String orderSummary) {
        // when
        String sut = WalletOrderPayment.normalizeOrderSummary(orderSummary);

        // then
        assertThat(sut).isNull();
    }

    static Stream<String> 빈_주문_요약() {
        return Stream.of(null, "", " ", "\t\n");
    }

    @Test
    void 주문_요약의_앞뒤_공백을_지운다() {
        // when
        String sut = WalletOrderPayment.normalizeOrderSummary("  사과 외 3건 ");

        // then
        assertThat(sut).isEqualTo("사과 외 3건");
    }

    @Test
    void 주문_요약이_칸_길이와_같으면_그대로_둔다() {
        // given
        String orderSummary = "가".repeat(WalletOrderPayment.MAX_ORDER_SUMMARY_LENGTH);

        // when
        String sut = WalletOrderPayment.normalizeOrderSummary(orderSummary);

        // then
        assertThat(sut).isEqualTo(orderSummary);
    }

    @Test
    void 주문_요약이_칸_길이를_넘으면_칸_길이만큼_자른다() {
        // given
        String orderSummary = "가".repeat(WalletOrderPayment.MAX_ORDER_SUMMARY_LENGTH) + " 외 3건";

        // when
        String sut = WalletOrderPayment.normalizeOrderSummary(orderSummary);

        // then
        assertThat(sut).isEqualTo("가".repeat(WalletOrderPayment.MAX_ORDER_SUMMARY_LENGTH));
    }

    @Test
    void 주문_요약을_자를_때_두_칸짜리_글자를_반으로_자르지_않는다() {
        // given: 이모지는 자바 문자 2개지만 DB에서는 한 글자다
        String emoji = "🍎";
        String orderSummary = emoji.repeat(WalletOrderPayment.MAX_ORDER_SUMMARY_LENGTH + 1);

        // when
        String sut = WalletOrderPayment.normalizeOrderSummary(orderSummary);

        // then
        assertThat(sut).isEqualTo(emoji.repeat(WalletOrderPayment.MAX_ORDER_SUMMARY_LENGTH));
    }
}
