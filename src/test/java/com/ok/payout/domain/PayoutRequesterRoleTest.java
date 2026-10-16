package com.ok.payout.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutRequesterRoleTest {

    @ParameterizedTest
    @MethodSource("역할_문자열과_해석_결과")
    void 역할_문자열을_정산_기준_역할로_해석한다(String role, PayoutRequesterRole expected) {
        // when
        PayoutRequesterRole sut = PayoutRequesterRole.from(role);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    // 모르는 값은 모두 권한 없는 OTHER로 해석되어야 함 (잘못된 값이 권한을 얻지 않도록)
    static Stream<Arguments> 역할_문자열과_해석_결과() {
        return Stream.of(
                Arguments.of("ADMIN", PayoutRequesterRole.ADMIN),
                Arguments.of("SELLER", PayoutRequesterRole.SELLER),
                Arguments.of("BUYER", PayoutRequesterRole.OTHER),
                Arguments.of("admin", PayoutRequesterRole.OTHER),   // 대소문자를 구분함
                Arguments.of("ADMN", PayoutRequesterRole.OTHER),
                Arguments.of("", PayoutRequesterRole.OTHER),
                Arguments.of(" ", PayoutRequesterRole.OTHER),
                Arguments.of(null, PayoutRequesterRole.OTHER));
    }
}
