package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.ok.delivery.fixture.ShipmentFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentAddressChangeRequestTest {

    private static final LocalDateTime PROCESSED_AT = NOW.plusHours(1);

    private static ShipmentAddressChangeRequest pendingRequest() {
        Shipment shipment = withId(shipmentIn(ShipmentStatus.PREPARING), 1L);
        return shipment.requestAddressChange(otherAddress(), 0, NOW);
    }

    @Test
    void 승인하면_승인_상태가_된다() {
        // given
        ShipmentAddressChangeRequest sut = pendingRequest();

        // when
        sut.approve(PROCESSED_AT);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(AddressChangeStatus.APPROVED);
            softly.assertThat(sut.getProcessedAt()).isEqualTo(PROCESSED_AT);
            softly.assertThat(sut.getRejectReason()).isNull();
        });
    }

    @Test
    void 거절하면_고정_문구가_사유로_남는다() {
        // given
        ShipmentAddressChangeRequest sut = pendingRequest();

        // when
        sut.reject(PROCESSED_AT);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(AddressChangeStatus.REJECTED);
            softly.assertThat(sut.getProcessedAt()).isEqualTo(PROCESSED_AT);
            softly.assertThat(sut.getRejectReason()).isEqualTo("상품 준비가 완료되어 배송지 변경이 불가능합니다.");
        });
    }

    @ParameterizedTest(name = "{0} 후 {1}")
    @MethodSource("reprocessCases")
    void 이미_처리된_요청은_다시_처리할_수_없다(String first, String second,
                                Consumer<ShipmentAddressChangeRequest> firstAction,
                                Consumer<ShipmentAddressChangeRequest> secondAction
    ) {
        // given
        ShipmentAddressChangeRequest sut = pendingRequest();
        firstAction.accept(sut);

        // when & then
        assertThatThrownBy(() -> secondAction.accept(sut))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(DeliveryErrorCode.ADDRESS_CHANGE_ALREADY_PROCESSED);
    }

    static Stream<Arguments> reprocessCases() {
        Consumer<ShipmentAddressChangeRequest> approve = request -> request.approve(PROCESSED_AT);
        Consumer<ShipmentAddressChangeRequest> reject = request -> request.reject(PROCESSED_AT);
        return Stream.of(
                Arguments.of("승인", "승인", approve, approve),
                Arguments.of("승인", "거절", approve, reject),
                Arguments.of("거절", "승인", reject, approve),
                Arguments.of("거절", "거절", reject, reject)
        );
    }

    @Test
    void 추가_운임_안내_금액은_음수일_수_없다() {
        // given
        Shipment shipment = withId(shipmentIn(ShipmentStatus.PREPARING), 1L);

        // when & then
        assertThatThrownBy(() -> shipment.requestAddressChange(otherAddress(), -1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
