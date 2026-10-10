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
            softly.assertThat(sut.isConfirmed()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("invalidItems")
    void 잘못된_값으로는_생성할_수_없다(Long orderItemId, String productName, int quantity, int unitPrice) {
        // when & then
        assertThatThrownBy(() -> ShipmentItem.create(orderItemId, productName, null, quantity, unitPrice))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidItems() {
        return Stream.of(
                Arguments.of(null, "무선 키보드", 1, 1_000),
                Arguments.of(1L, " ", 1, 1_000),
                Arguments.of(1L, "무선 키보드", 0, 1_000),
                Arguments.of(1L, "무선 키보드", 1, -1)
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
    void 취소_수량은_1_이상이고_누적이_주문_수량을_넘을_수_없다(int quantity) {
        // given
        ShipmentItem sut = item(1L);

        // when & then
        assertThatThrownBy(() -> sut.cancel(quantity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sut.getCanceledQuantity()).isZero();
    }

    static Stream<Integer> invalidCancelQuantities() {
        return Stream.of(0, -1, 3);
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
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE);
    }
}
