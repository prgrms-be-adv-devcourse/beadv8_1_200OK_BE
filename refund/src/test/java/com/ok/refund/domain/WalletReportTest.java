package com.ok.refund.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 예시: 환불 7번(주문 1000번, 구매자 5번, 10,000원)에 지갑이 "구매자 6번에게 10,000원 완료"처럼 다른 값을 보냈다(#761).
// 반쪽짜리나 말이 안 되는 기록(완료인데 실패 이유가 있음 등)이 만들어지지 않는지 본다. DB CHECK와 같은 규칙이다
class WalletReportTest {

    @Test
    void 완료_결과는_실패_이유_없이_만든다() {
        // when
        WalletReport sut = WalletReport.completed(1_000L, 6L, 10_000L);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.result()).isEqualTo(WalletReport.Result.COMPLETED);
            softly.assertThat(sut.orderId()).isEqualTo(1_000L);
            softly.assertThat(sut.buyerId()).isEqualTo(6L);
            softly.assertThat(sut.amount()).isEqualTo(10_000L);
            softly.assertThat(sut.reason()).isNull();
        });
    }

    @Test
    void 실패_결과는_실패_이유와_함께_만든다() {
        // when
        WalletReport sut = WalletReport.failed(1_000L, 6L, 10_000L, "PAYMENT_NOT_FOUND");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.result()).isEqualTo(WalletReport.Result.FAILED);
            softly.assertThat(sut.reason()).isEqualTo("PAYMENT_NOT_FOUND");
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("말이_안_되는_값")
    void 반쪽짜리나_말이_안_되는_값은_만들지_않는다(String 경우, WalletReport.Result result, Long orderId, Long buyerId,
                                       long amount, String reason, String expectedMessage) {
        // when & then
        assertThatThrownBy(() -> new WalletReport(result, orderId, buyerId, amount, reason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
    }

    static Stream<Arguments> 말이_안_되는_값() {
        return Stream.of(
                Arguments.of("결과 종류 없음", null, 1_000L, 6L, 10_000L, null,
                        "지갑 결과 종류·주문·구매자는 비어 있을 수 없습니다. result=null, orderId=1000, buyerId=6"),
                Arguments.of("주문 없음", WalletReport.Result.COMPLETED, null, 6L, 10_000L, null,
                        "지갑 결과 종류·주문·구매자는 비어 있을 수 없습니다. result=COMPLETED, orderId=null, buyerId=6"),
                Arguments.of("구매자 없음", WalletReport.Result.COMPLETED, 1_000L, null, 10_000L, null,
                        "지갑 결과 종류·주문·구매자는 비어 있을 수 없습니다. result=COMPLETED, orderId=1000, buyerId=null"),
                Arguments.of("0원", WalletReport.Result.COMPLETED, 1_000L, 6L, 0L, null,
                        "지갑이 보낸 금액은 0보다 커야 합니다. amount=0"),
                Arguments.of("완료인데 실패 이유 있음", WalletReport.Result.COMPLETED, 1_000L, 6L, 10_000L, "PAYMENT_NOT_FOUND",
                        "완료 결과에는 실패 이유가 없어야 합니다. reason=PAYMENT_NOT_FOUND"),
                Arguments.of("실패인데 이유 없음", WalletReport.Result.FAILED, 1_000L, 6L, 10_000L, null,
                        "실패 결과에는 실패 이유가 있어야 합니다."),
                Arguments.of("실패인데 이유가 공백", WalletReport.Result.FAILED, 1_000L, 6L, 10_000L, " ",
                        "실패 결과에는 실패 이유가 있어야 합니다."),
                Arguments.of("실패 이유가 칸보다 김", WalletReport.Result.FAILED, 1_000L, 6L, 10_000L, "가".repeat(101),
                        "지갑이 보낸 실패 이유가 너무 깁니다. 최대 100자, length=101"));
    }
}
