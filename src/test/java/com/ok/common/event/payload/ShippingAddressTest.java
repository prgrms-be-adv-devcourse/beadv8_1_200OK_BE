package com.ok.common.event.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShippingAddressTest {

    @ParameterizedTest
    @MethodSource("필수값이_비어_있는_배송지")
    void 필수값이_비어_있으면_예외가_발생한다(String recipientName, String recipientPhone, String postalCode,
                                  String address, String addressDetail, String message) {
        // when & then
        assertThatThrownBy(() -> new ShippingAddress(recipientName, recipientPhone, postalCode, address, addressDetail, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> 필수값이_비어_있는_배송지() {
        return Stream.of(
                Arguments.of(null, "01012345678", "06236", "서울", "101호", "recipientName는 필수입니다."),
                Arguments.of("홍길동", "", "06236", "서울", "101호", "recipientPhone는 필수입니다."),
                Arguments.of("홍길동", "01012345678", " ", "서울", "101호", "postalCode는 필수입니다."),
                Arguments.of("홍길동", "01012345678", "06236", "", "101호", "address는 필수입니다."),
                Arguments.of("홍길동", "01012345678", "06236", "서울", " ", "addressDetail는 필수입니다.")
        );
    }

    @Test
    void toString에_개인정보가_노출되지_않는다() {
        // given
        ShippingAddress address = new ShippingAddress("홍길동", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", "101동 202호", "문 앞");

        // when
        String sut = address.toString();

        // then
        assertThat(sut)
                .doesNotContain("홍길동", "010-1234-5678", "서울 강남구 테헤란로 1", "101동 202호", "문 앞")
                .contains("postalCode=06236");
    }
}
