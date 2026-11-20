package com.ok.wallet.domain;

import com.ok.common.exception.RestApiException;
import com.ok.wallet.exception.WalletErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 16, 12, 0);

    // 충전 요청

    @Test
    void 충전을_요청하면_대기_상태로_만들어지고_충전_번호가_붙는다() {
        // When
        Charge charge = Charge.request(1L, 10_000);

        // Then
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PENDING);
        assertThat(charge.getChargeNo()).startsWith("CHG_").hasSize(40);
        assertThat(charge.getAmount()).isEqualTo(10_000);
    }

    @Test
    void 충전_번호는_요청마다_다르다() {
        assertThat(Charge.request(1L, 10_000).getChargeNo())
                .isNotEqualTo(Charge.request(1L, 10_000).getChargeNo());
    }

    @ParameterizedTest
    @MethodSource("만원_이상_금액")
    void 만원_이상이면_단위와_관계없이_요청된다(long amount) {
        assertThat(Charge.request(1L, amount).getAmount()).isEqualTo(amount);
    }

    static Stream<Long> 만원_이상_금액() {
        return Stream.of(10_000L, 10_001L, 15_000L, 500_000L);
    }

    @ParameterizedTest
    @MethodSource("만원_미만_금액")
    void 만원_미만이면_요청되지_않는다(long amount) {
        assertThatThrownBy(() -> Charge.request(1L, amount))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(WalletErrorCode.INVALID_CHARGE_AMOUNT));
    }

    static Stream<Long> 만원_미만_금액() {
        return Stream.of(0L, -10_000L, 5_000L, 9_999L);
    }

    // 확인

    @Test
    void 요청한_회원의_충전_건인지_확인한다() {
        Charge charge = Charge.request(1L, 10_000);

        assertThat(charge.isOwnedBy(1L)).isTrue();
        assertThat(charge.isOwnedBy(2L)).isFalse();
    }

    @Test
    void 요청한_금액과_같은지_확인한다() {
        Charge charge = Charge.request(1L, 10_000);

        assertThat(charge.hasAmount(10_000)).isTrue();
        assertThat(charge.hasAmount(20_000)).isFalse();
    }

    // 상태 변경

    @Test
    void 대기_중인_충전_건은_실패로_바꿀_수_있다() {
        // Given
        Charge charge = Charge.request(1L, 10_000);

        // When: 토스 결제창에서 결제 실패
        charge.fail("PAY_PROCESS_CANCELED", "사용자가 결제를 취소했습니다.", NOW);

        // Then
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.FAILED);
        assertThat(charge.getFailureCode()).isEqualTo("PAY_PROCESS_CANCELED");
        assertThat(charge.getFailedAt()).isEqualTo(NOW);
    }

    @Test
    void 대기_중인_충전_건은_승인_호출_없이_완료할_수_없다() {
        Charge charge = Charge.request(1L, 10_000);

        assertThatThrownBy(() -> charge.complete("payment-key", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(charge.getStatus()).isEqualTo(ChargeStatus.PENDING);
    }

    @Test
    void 실패한_충전_건은_다시_바꿀_수_없다() {
        // Given
        Charge charge = Charge.request(1L, 10_000);
        charge.fail("PAY_PROCESS_CANCELED", "사용자가 결제를 취소했습니다.", NOW);

        // When & Then
        assertThatThrownBy(() -> charge.fail("OTHER", "다른 실패", NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(charge.getFailureCode()).isEqualTo("PAY_PROCESS_CANCELED");
    }
}
