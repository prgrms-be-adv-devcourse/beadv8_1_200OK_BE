package com.ok.payout.service;

import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.repository.PayoutQueryRepository.FailureRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 결과 검증 실패로 뺄 draft 항목 기준만 확인하는 단위 테스트 (#290)
class PayoutVerifyExclusionTest {

    @Test
    void 결과_검증_실패_주문의_항목은_다른_후보라도_뺀다() {
        // given: 주문 1의 후보 3번이 결과 검증 실패
        PayoutVerifyExclusion sut = PayoutVerifyExclusion.from(
                List.of(new FailureRow(3L, 1L, PayoutFailureReason.VERIFY_MISMATCH)));

        // when & then: 같은 주문의 후보 4번(함께 보류 대상)도 빠짐
        assertThat(sut.excludes(1L, 4L)).isTrue();
    }

    @Test
    void 결과_검증_실패_후보의_항목은_주문_ID가_다르게_들어갔어도_뺀다() {
        // given: 후보 3번(실제 주문 1)이 결과 검증 실패, 그런데 draft 항목에는 주문 9로 들어감
        PayoutVerifyExclusion sut = PayoutVerifyExclusion.from(
                List.of(new FailureRow(3L, 1L, PayoutFailureReason.VERIFY_MISMATCH)));

        // when & then: 주문 기준으로는 못 거르므로 후보 기준으로 뺌
        assertThat(sut.excludes(9L, 3L)).isTrue();
    }

    @Test
    void 다른_사유의_실패_기록이나_상관없는_항목은_빼지_않는다() {
        // given: 주문 2는 validate 실패만 있음 (그 후보는 애초에 draft에 없고, 같은 주문의 나머지는 정상 정산 대상)
        PayoutVerifyExclusion sut = PayoutVerifyExclusion.from(
                List.of(new FailureRow(5L, 2L, PayoutFailureReason.DEDUCTION_MISSING)));

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(sut.excludes(2L, 6L)).isFalse();
            softly.assertThat(sut.excludes(7L, 8L)).isFalse();
            softly.assertThat(sut.orderCount()).isZero();
        });
    }
}
