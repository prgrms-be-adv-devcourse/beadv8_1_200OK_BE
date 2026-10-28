package com.ok.wallet.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeStatusTest {
    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "PENDING, IN_PROGRESS, true",
            "PENDING, FAILED, true",
            "PENDING, COMPLETED, false",   // 승인 호출 없이 완료될 수 없음
            "IN_PROGRESS, COMPLETED, true",
            "IN_PROGRESS, FAILED, true",
            "IN_PROGRESS, PENDING, false",
            "COMPLETED, FAILED, false",    // 끝 상태는 바뀌지 않음
            "COMPLETED, IN_PROGRESS, false",
            "FAILED, COMPLETED, false",
            "FAILED, PENDING, false"
    })
    void 정해진_순서로만_상태가_바뀐다(ChargeStatus from, ChargeStatus to, boolean expected) {
        assertThat(from.canTransitionTo(to)).isEqualTo(expected);
    }
}
