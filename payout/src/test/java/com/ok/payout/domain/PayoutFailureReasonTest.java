package com.ok.payout.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 실패 사유 enum의 고정값 확인 (DB 칸 없이 enum으로 계산하는 값이라, 값이 바뀌면 과거 기록의 동작도 함께 바뀜)
class PayoutFailureReasonTest {

    @Test
    void 주문을_잠그는_사유는_결과_검증_불일치뿐이다() {
        // when
        List<PayoutFailureReason> sut = PayoutFailureReason.orderLockingReasons();

        // then: #552부터 금액 불일치(AMOUNT_MISMATCH·FEE_MISMATCH)는 잠그지 않고 다시 검사함. 코드 버그인 결과 검증 불일치만 잠금
        assertThat(sut).containsExactly(PayoutFailureReason.VERIFY_MISMATCH);
    }

    @Test
    void 실패_횟수에_세지_않는_사유는_수수료_불일치와_그_때문에_함께_보류된_것뿐이다() {
        // when
        List<PayoutFailureReason> sut = PayoutFailureReason.uncountedReasons();

        // then: 시스템 문제라 횟수로 멈추지 않고 고칠 때까지 다시 검사함 (#552). 초과 청구는 세어 3번이면 멈춤(#549)
        assertThat(sut).containsExactly(PayoutFailureReason.FEE_MISMATCH, PayoutFailureReason.ORDER_HELD);
    }

    // 사유를 새로 더할 때 범주를 빠뜨리지 않게 모든 사유를 돌며 확인
    @ParameterizedTest
    @EnumSource(PayoutFailureReason.class)
    void 모든_사유에는_범주가_있다(PayoutFailureReason reason) {
        // when
        PayoutFailureCategory sut = reason.getCategory();

        // then
        assertThat(sut).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("사유별_범주")
    void 사유의_범주는_정해진_값이다(PayoutFailureReason reason, PayoutFailureCategory expected) {
        // when
        PayoutFailureCategory sut = reason.getCategory();

        // then
        assertThat(sut).isEqualTo(expected);
    }

    // #391에서 정한 매핑. ORDER_HELD는 수수료 불일치 때만 생기므로 원인을 따라 SYSTEM
    static Stream<Arguments> 사유별_범주() {
        return Stream.of(
                Arguments.of(PayoutFailureReason.DEDUCTION_MISSING, PayoutFailureCategory.SOURCE_DATA),
                Arguments.of(PayoutFailureReason.ORDER_MISSING, PayoutFailureCategory.SOURCE_DATA),
                Arguments.of(PayoutFailureReason.AMOUNT_MISMATCH, PayoutFailureCategory.SOURCE_DATA),
                Arguments.of(PayoutFailureReason.FEE_MISMATCH, PayoutFailureCategory.SYSTEM),
                Arguments.of(PayoutFailureReason.UNEXPECTED_ERROR, PayoutFailureCategory.SYSTEM),
                Arguments.of(PayoutFailureReason.VERIFY_MISMATCH, PayoutFailureCategory.SYSTEM),
                Arguments.of(PayoutFailureReason.ORDER_HELD, PayoutFailureCategory.SYSTEM));
    }

    @Test
    void 사유별_범주_매핑은_모든_사유를_다룬다() {
        // when: 위 매핑 목록에 든 사유
        List<Object> mapped = 사유별_범주().map(arguments -> arguments.get()[0]).toList();

        // then: 사유가 늘었는데 매핑 확인을 빠뜨리면 여기서 걸림
        assertThat(mapped).containsExactlyInAnyOrder((Object[]) PayoutFailureReason.values());
    }

    @Test
    void 대사_문제_범주를_쓰는_사유는_아직_없다() {
        // given: #308(대사)용으로 미리 둔 범주
        int count = 0;

        // when
        for (PayoutFailureReason reason : PayoutFailureReason.values()) {
            if (reason.getCategory() == PayoutFailureCategory.RECONCILIATION) {
                count++;
            }
        }

        // then
        assertThat(count).isZero();
    }
}
