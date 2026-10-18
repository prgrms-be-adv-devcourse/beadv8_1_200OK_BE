package com.ok.delivery.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteAreaPostalCodeHistoryTest {

    @ParameterizedTest
    @MethodSource("필수값이_빠진_이력")
    void 필수값이_없으면_이력을_만들_수_없다(PostalCodeHistoryAction action, RemoteAreaPostalCode postalCode, Long changedBy,
                                 String message) {
        // when & then
        assertThatThrownBy(() -> RemoteAreaPostalCodeHistory.of(action, postalCode, changedBy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 필수값이_빠진_이력() {
        RemoteAreaPostalCode postalCode = RemoteAreaPostalCode.of("63000", "JEJU_ISLANDS", 2);
        return Stream.of(
                Arguments.of(null, postalCode, 1L, "이력 동작은 필수입니다."),
                Arguments.of(PostalCodeHistoryAction.REGISTERED, null, 1L, "우편번호는 필수입니다."),
                Arguments.of(PostalCodeHistoryAction.REGISTERED, postalCode, null, "변경한 관리자 ID는 필수입니다.")
        );
    }
}
