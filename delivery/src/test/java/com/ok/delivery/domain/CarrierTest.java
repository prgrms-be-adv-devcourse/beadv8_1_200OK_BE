package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CarrierTest {

    @ParameterizedTest
    @MethodSource("carriers")
    void 택배사_코드로_택배사를_찾는다(Carrier carrier) {
        // when
        Carrier sut = Carrier.from(carrier.name());

        // then
        assertThat(sut).isEqualTo(carrier);
    }

    static Stream<Carrier> carriers() {
        return Arrays.stream(Carrier.values());
    }

    @ParameterizedTest
    @MethodSource("invalidCodes")
    void 비었거나_없는_택배사_코드는_입력_오류다(String code, DeliveryErrorCode errorCode) {
        // when & then
        assertThatThrownBy(() -> Carrier.from(code))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    static Stream<Arguments> invalidCodes() {
        return Stream.of(
                Arguments.of(null, DeliveryErrorCode.MISSING_CARRIER),
                Arguments.of("", DeliveryErrorCode.MISSING_CARRIER),
                Arguments.of(" ", DeliveryErrorCode.MISSING_CARRIER),
                Arguments.of("FEDEX", DeliveryErrorCode.INVALID_CARRIER),
                Arguments.of("cj_logistics", DeliveryErrorCode.INVALID_CARRIER)
        );
    }
}
