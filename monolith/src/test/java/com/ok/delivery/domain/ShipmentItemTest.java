package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.ok.delivery.fixture.ShipmentFixture.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentItemTest {

    @Test
    void 생성하면_취소_수량은_0이고_구매확정_전이다() {
        // when
        ShipmentItem sut = ShipmentItem.create(1L, "무선 키보드", "블랙", 2, 30_000);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getOrderItemId()).isEqualTo(1L);
            softly.assertThat(sut.getProductName()).isEqualTo("무선 키보드");
            softly.assertThat(sut.getOptionName()).isEqualTo("블랙");
            softly.assertThat(sut.getQuantity()).isEqualTo(2);
            softly.assertThat(sut.getUnitPrice()).isEqualTo(30_000);
            softly.assertThat(sut.getCanceledQuantity()).isZero();
            softly.assertThat(sut.getReservedCancelQuantity()).isZero();
            softly.assertThat(sut.isConfirmed()).isFalse();
        });
    }

    @Test
    void orderItemId가_없으면_생성할_수_없다() {
        // when & then
        assertThatThrownBy(() -> ShipmentItem.create(null, "무선 키보드", null, 1, 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderItemId는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void 잘못된_값으로는_생성할_수_없다(String productName, int quantity, int unitPrice, String message) {
        // when & then
        assertThatThrownBy(() -> ShipmentItem.create(1L, productName, null, quantity, unitPrice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    static Stream<Arguments> invalidItems() {
        return Stream.of(
                Arguments.of(null, 1, 1_000, "상품명은 필수입니다."),
                Arguments.of(" ", 1, 1_000, "상품명은 필수입니다."),
                Arguments.of("무선 키보드", 0, 1_000, "수량은 1 이상이어야 합니다."),
                Arguments.of("무선 키보드", 1, -1, "단가는 0 이상이어야 합니다.")
        );
    }

    @Test
    void 취소_수량은_누적된다() {
        // given
        ShipmentItem sut = item(1L);

        // when
        sut.cancel(1);
        sut.cancel(1);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getCanceledQuantity()).isEqualTo(2);
            softly.assertThat(sut.isFullyCanceled()).isTrue();
        });
    }

    @Test
    void 일부만_취소하면_전량_취소가_아니다() {
        // given
        ShipmentItem sut = item(1L);

        // when
        sut.cancel(1);

        // then
        assertThat(sut.isFullyCanceled()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("invalidCancelQuantities")
    void 취소_수량은_1_이상이고_누적이_주문_수량을_넘을_수_없다(int quantity, DeliveryErrorCode errorCode) {
        // given
        ShipmentItem sut = item(1L);

        // when & then
        assertThatThrownBy(() -> sut.cancel(quantity))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
        assertThat(sut.getCanceledQuantity()).isZero();
    }

    // item(1L)의 주문 수량은 2
    static Stream<Arguments> invalidCancelQuantities() {
        return Stream.of(
                Arguments.of(0, DeliveryErrorCode.INVALID_CANCEL_QUANTITY),
                Arguments.of(-1, DeliveryErrorCode.INVALID_CANCEL_QUANTITY),
                Arguments.of(3, DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED)
        );
    }

    @Test
    void 취소_확정_예약_수량은_누적된다() {
        // given
        ShipmentItem sut = item(1L);

        // when
        sut.reserveCancel(1);
        sut.reserveCancel(1);

        // then
        assertThat(sut.getReservedCancelQuantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("invalidReserveQuantities")
    void 예약_수량은_1_이상이고_취소_수량과_예약_수량의_합이_주문_수량을_넘을_수_없다(
            int canceled, int reserved, int reserveQuantity, DeliveryErrorCode errorCode) {
        // given
        ShipmentItem sut = item(1L);
        if (canceled > 0) {
            sut.cancel(canceled);
        }
        if (reserved > 0) {
            sut.reserveCancel(reserved);
        }

        // when & then
        assertThatThrownBy(() -> sut.reserveCancel(reserveQuantity))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    // 픽스처 항목의 주문 수량은 2다
    static Stream<Arguments> invalidReserveQuantities() {
        return Stream.of(
                Arguments.of(0, 0, 0, DeliveryErrorCode.INVALID_CANCEL_QUANTITY),
                Arguments.of(0, 0, -1, DeliveryErrorCode.INVALID_CANCEL_QUANTITY),
                Arguments.of(0, 0, 3, DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED),
                Arguments.of(1, 0, 2, DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED),
                Arguments.of(0, 1, 2, DeliveryErrorCode.CANCEL_QUANTITY_EXCEEDED)
        );
    }

    @Test
    void 구매확정한다() {
        // given
        ShipmentItem sut = item(1L);

        // when
        sut.confirm();

        // then
        assertThat(sut.isConfirmed()).isTrue();
    }

    @Test
    void 이미_구매확정된_항목을_다시_확정해도_그대로다() {
        // given
        ShipmentItem sut = item(1L);
        sut.confirm();

        // when
        sut.confirm();

        // then
        assertThat(sut.isConfirmed()).isTrue();
    }

    @Test
    void 전량_취소된_항목은_구매확정할_수_없다() {
        // given
        ShipmentItem sut = item(1L);
        sut.cancel(sut.getQuantity());

        // when & then
        assertThatThrownBy(sut::confirm)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE));
    }
}
