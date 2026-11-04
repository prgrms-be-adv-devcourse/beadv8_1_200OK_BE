package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundTest {

    @Test
    void 요청하면_요청됨으로_만들고_상품_금액을_계산하고_진행_중_칸을_채운다() {
        // when
        Refund sut = Refund.request(10L, 101L, 2, 1L, 2L, 55L, "양말", "흰색", 10_000L, "옵션을 잘못 골랐습니다.");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getOrderId()).isEqualTo(10L);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getQuantity()).isEqualTo(2);
            softly.assertThat(sut.getBuyerId()).isEqualTo(1L);
            softly.assertThat(sut.getSellerId()).isEqualTo(2L);
            softly.assertThat(sut.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(sut.getProductName()).isEqualTo("양말");
            softly.assertThat(sut.getOptionName()).isEqualTo("흰색");
            softly.assertThat(sut.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    @Test
    void 취소_사유는_없어도_된다() {
        // when
        Refund sut = Refund.request(10L, 101L, 1, 1L, 2L, 55L, "양말", "흰색", 10_000L, null);

        // then
        assertThat(sut.getCancelReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("일보다_작은_개수")
    void 개수가_1_미만이면_막는다(int quantity) {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, quantity, 1L, 2L, 55L, "양말", "흰색", 10_000L, null))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_QUANTITY));
    }

    static Stream<Integer> 일보다_작은_개수() {
        return Stream.of(0, -1);
    }

    @Test
    void 주문에서_받은_ID가_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, null, 55L, "양말", "흰색", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문·주문 상품·구매자·판매자·옵션 ID는 필수입니다.");
    }

    @Test
    void 주문에서_받은_이름이_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, null, "흰색", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품명과 옵션명은 필수입니다.");
    }

    @Test
    void 단가가_음수면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, "양말", "흰색", -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("단가는 0 이상이어야 합니다.");
    }

    @Test
    void 상품_금액이_long을_넘으면_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 2, 1L, 2L, 55L, "양말", "흰색", Long.MAX_VALUE, null))
                .isInstanceOf(ArithmeticException.class);
    }
}
