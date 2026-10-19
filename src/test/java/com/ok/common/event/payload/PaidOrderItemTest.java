package com.ok.common.event.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaidOrderItemTest {

    @ParameterizedTest
    @MethodSource("비어_있는_상품명")
    void 상품명이_비어_있으면_예외가_발생한다(String productName) {
        // when & then
        assertThatThrownBy(() -> new PaidOrderItem(100L, productName, null, 1, 10_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("productName은 필수입니다.");
    }

    static Stream<String> 비어_있는_상품명() {
        return Stream.of(null, "", " ");
    }

    @Test
    void 수량이_1_미만이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new PaidOrderItem(100L, "사과", null, 0, 10_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quantity는 1 이상이어야 합니다.");
    }

    @Test
    void 단가가_음수면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> new PaidOrderItem(100L, "사과", null, 1, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unitPrice는 0 이상이어야 합니다.");
    }

    @Test
    void 수량_1_단가_0이면_만들_수_있다() {
        // when
        PaidOrderItem sut = new PaidOrderItem(100L, "사과", null, 1, 0L);

        // then
        assertThat(sut.unitPrice()).isZero();
    }
}
