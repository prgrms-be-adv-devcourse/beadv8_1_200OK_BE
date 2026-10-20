package com.ok.delivery.domain;

import com.ok.common.exception.RestApiException;
import com.ok.delivery.exception.DeliveryErrorCode;
import com.ok.delivery.fixture.ShipmentFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.ok.delivery.fixture.ShipmentFixture.NOW;
import static com.ok.delivery.fixture.ShipmentFixture.address;
import static com.ok.delivery.fixture.ShipmentFixture.item;
import static com.ok.delivery.fixture.ShipmentFixture.otherAddress;
import static com.ok.delivery.fixture.ShipmentFixture.shipment;
import static com.ok.delivery.fixture.ShipmentFixture.shipmentIn;
import static com.ok.delivery.fixture.ShipmentFixture.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class ShipmentTest {

    /**
     * 상태별로 허용된 전이. 상태 전이 다이어그램의 화살표와 1:1로 대응한다.
     *
     * <pre>
     * PREPARING --DISPATCH--> SHIPPING   (판매자 송장 등록)
     * SHIPPING  --DELIVER---> DELIVERED  (택배사 배송 완료 알림)
     * DELIVERED --COMPLETE--> COMPLETED  (전량 취소 항목을 뺀 모든 항목 구매확정)
     * PREPARING --CANCEL----> CANCELLED  (모든 항목 전량 취소)
     * </pre>
     *
     * <p>여기에 없는 (상태, 전이) 조합은 모두 {@code INVALID_SHIPMENT_STATE}가 되어야 한다.
     * {@code COMPLETED}, {@code CANCELLED}는 종료 상태라 허용된 전이가 없다.
     *
     * @see #invalidTransitions()
     */
    private static final Map<ShipmentStatus, Set<Transition>> ALLOWED = Map.of(
            ShipmentStatus.PREPARING, EnumSet.of(Transition.DISPATCH, Transition.CANCEL),
            ShipmentStatus.SHIPPING, EnumSet.of(Transition.DELIVER),
            ShipmentStatus.DELIVERED, EnumSet.of(Transition.COMPLETE)
    );

    enum Transition {
        DISPATCH(shipment -> shipment.dispatch(Carrier.HANJIN, "9876543210", NOW)),
        DELIVER(shipment -> shipment.deliver(NOW)),
        COMPLETE(shipment -> shipment.complete(NOW)),
        CANCEL(shipment -> shipment.cancel(NOW));

        private final Consumer<Shipment> action;

        Transition(Consumer<Shipment> action) {
            this.action = action;
        }
    }

    @Test
    void 생성하면_상품_준비_상태이고_항목이_연결된다() {
        // given
        ShipmentItem item = item(1L);

        // when
        Shipment sut = shipment(item);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.PREPARING);
            softly.assertThat(sut.isDelayed()).isFalse();
            softly.assertThat(sut.getCarrier()).isNull();
            softly.assertThat(sut.getTrackingNumber()).isNull();
            softly.assertThat(sut.getAddress()).isEqualTo(address());
            softly.assertThat(sut.getItems()).containsExactly(item);
            softly.assertThat(item.getShipment()).isSameAs(sut);
        });
    }

    @Test
    void 필수값이_없으면_생성할_수_없다() {
        // given
        var sut = Shipment.builder()
                .sellerId(10L)
                .address(address())
                .regionGroup("GENERAL")
                .items(List.of(item(1L)));

        // when & then
        assertThatThrownBy(sut::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orderId는 필수입니다.");
    }

    @Test
    void 상품_항목이_없으면_생성할_수_없다() {
        // when & then
        assertThatThrownBy(ShipmentFixture::shipment)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품 항목은 1개 이상이어야 합니다.");
    }

    @Test
    void 배송비가_음수면_생성할_수_없다() {
        // given
        var sut = validBuilder().regionSurcharge(-1);

        // when & then
        assertThatThrownBy(sut::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 금액은 0 이상이어야 합니다.");
    }

    @Test
    void 부과한_배송비가_음수면_생성할_수_없다() {
        // given
        var sut = validBuilder().chargedShippingFee(-1);

        // when & then
        assertThatThrownBy(sut::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("배송비 금액은 0 이상이어야 합니다.");
    }

    @Test
    void 무료배송이어도_결제_당시_기본_배송비와_부과한_배송비를_따로_남긴다() {
        // when
        Shipment sut = validBuilder()
                .baseShippingFee(3_000)
                .chargedShippingFee(0)
                .freeShippingThreshold(50_000)
                .build();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getBaseShippingFee()).isEqualTo(3_000);
            softly.assertThat(sut.getChargedShippingFee()).isZero();
            softly.assertThat(sut.getFreeShippingThreshold()).isEqualTo(50_000);
        });
    }

    @Test
    void 도착_예정일_시작이_끝보다_늦으면_생성할_수_없다() {
        // given
        var sut = validBuilder()
                .estimatedDeliveryStart(LocalDate.of(2026, 10, 15))
                .estimatedDeliveryEnd(LocalDate.of(2026, 10, 14));

        // when & then
        assertThatThrownBy(sut::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("도착 예정일 시작은 끝보다 늦을 수 없습니다.");
    }

    private static Shipment.ShipmentBuilder validBuilder() {
        return Shipment.builder()
                .orderId(1L)
                .sellerId(10L)
                .address(address())
                .regionGroup("GENERAL")
                .estimatedDeliveryStart(LocalDate.of(2026, 10, 13))
                .estimatedDeliveryEnd(LocalDate.of(2026, 10, 14))
                .items(List.of(item(1L)));
    }

    @Test
    void 송장을_등록하면_배송중이_된다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);

        // when
        sut.dispatch(Carrier.CJ_LOGISTICS, "1234567890", NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.SHIPPING);
            softly.assertThat(sut.getCarrier()).isEqualTo(Carrier.CJ_LOGISTICS);
            softly.assertThat(sut.getTrackingNumber()).isEqualTo("1234567890");
            softly.assertThat(sut.getShippedAt()).isEqualTo(NOW);
        });
    }

    @ParameterizedTest
    @MethodSource("missingDispatchValues")
    void 택배사나_송장번호가_없으면_송장을_등록할_수_없다(Carrier carrier, String trackingNumber, DeliveryErrorCode errorCode) {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);

        // when & then
        assertThatThrownBy(() -> sut.dispatch(carrier, trackingNumber, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(errorCode));
    }

    static Stream<Arguments> missingDispatchValues() {
        return Stream.of(
                Arguments.of(null, "1234567890", DeliveryErrorCode.MISSING_CARRIER),
                Arguments.of(Carrier.CJ_LOGISTICS, null, DeliveryErrorCode.MISSING_TRACKING_NUMBER),
                Arguments.of(Carrier.CJ_LOGISTICS, "", DeliveryErrorCode.MISSING_TRACKING_NUMBER),
                Arguments.of(Carrier.CJ_LOGISTICS, " ", DeliveryErrorCode.MISSING_TRACKING_NUMBER)
        );
    }

    @ParameterizedTest
    @MethodSource("validTrackingNumbers")
    void 송장번호가_숫자_10자리에서_13자리면_송장을_등록할_수_있다(String trackingNumber) {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);

        // when
        sut.dispatch(Carrier.CJ_LOGISTICS, trackingNumber, NOW);

        // then
        assertThat(sut.getTrackingNumber()).isEqualTo(trackingNumber);
    }

    static Stream<String> validTrackingNumbers() {
        return Stream.of("1234567890", "1234567890123");
    }

    @ParameterizedTest
    @MethodSource("invalidTrackingNumbers")
    void 송장번호가_숫자_10자리에서_13자리가_아니면_송장을_등록할_수_없다(String trackingNumber) {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);

        // when & then
        assertThatThrownBy(() -> sut.dispatch(Carrier.CJ_LOGISTICS, trackingNumber, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_TRACKING_NUMBER));
    }

    static Stream<String> invalidTrackingNumbers() {
        return Stream.of(
                "123456789",        // 9자리
                "12345678901234",   // 14자리
                "12345-67890",      // 하이픈
                "12345678ab",       // 영문
                " 1234567890",      // 앞 공백
                "１２３４５６７８９０"   // 전각 숫자
        );
    }

    @Test
    void 취소_확정_예약된_항목이_있으면_송장을_등록할_수_없다() {
        // given
        Shipment sut = shipment(item(1L), item(2L));
        sut.reserveCancel(2L, 1);

        // when & then
        assertThatThrownBy(() -> sut.dispatch(Carrier.CJ_LOGISTICS, "1234567890", NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.CANCELLATION_RESERVED));
        assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.PREPARING);
    }

    @Test
    void 송장_발행_전이면_항목의_취소_확정을_예약할_수_있다() {
        // given
        Shipment shipment = shipment(item(1L), item(2L));

        // when
        shipment.reserveCancel(2L, 1);

        // then
        ShipmentItem sut = shipment.getItems().get(1);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getReservedCancelQuantity()).isEqualTo(1);
            softly.assertThat(sut.hasReservedCancel()).isTrue();
            softly.assertThat(shipment.getItems().get(0).hasReservedCancel()).isFalse();
        });
    }

    @ParameterizedTest
    @MethodSource("statesExceptPreparing")
    void 송장_발행_전이_아니면_취소_확정을_예약할_수_없다(ShipmentStatus status) {
        // given
        Shipment sut = shipmentIn(status);

        // when & then
        assertThatThrownBy(() -> sut.reserveCancel(1L, 1))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE));
    }

    static Stream<ShipmentStatus> statesExceptPreparing() {
        return Stream.of(ShipmentStatus.SHIPPING, ShipmentStatus.DELIVERED, ShipmentStatus.COMPLETED, ShipmentStatus.CANCELLED);
    }

    @Test
    void shipment에_없는_주문_상품은_예약할_수_없다() {
        // given
        Shipment sut = shipment(item(1L));

        // when & then
        assertThatThrownBy(() -> sut.reserveCancel(99L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("shipment에 없는 주문 상품입니다. orderItemId=99");
    }

    @Test
    void 배송이_완료되면_배송완료가_된다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.SHIPPING);

        // when
        sut.deliver(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.DELIVERED);
            softly.assertThat(sut.getDeliveredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 모든_항목이_구매확정되면_거래완료가_된다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.DELIVERED);
        sut.getItems().forEach(ShipmentItem::confirm);

        // when
        sut.complete(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.COMPLETED);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 전량_취소된_항목은_거래완료_조건에서_제외된다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.DELIVERED);
        ShipmentItem canceled = sut.getItems().get(0);
        ShipmentItem confirmed = sut.getItems().get(1);
        canceled.cancel(canceled.getQuantity());
        confirmed.confirm();

        // when
        sut.complete(NOW);

        // then
        assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.COMPLETED);
    }

    @Test
    void 구매확정되지_않은_항목이_있으면_거래완료가_될_수_없다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.DELIVERED);
        sut.getItems().get(0).confirm();

        // when & then
        assertThatThrownBy(() -> sut.complete(NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE));
    }

    @Test
    void 모든_항목이_전량_취소되면_취소가_된다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);
        sut.getItems().forEach(item -> item.cancel(item.getQuantity()));

        // when
        sut.cancel(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(ShipmentStatus.CANCELLED);
            softly.assertThat(sut.getCancelledAt()).isEqualTo(NOW);
        });
    }

    @Test
    void 일부만_취소되면_취소가_될_수_없다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);
        sut.getItems().get(0).cancel(1);

        // when & then
        assertThatThrownBy(() -> sut.cancel(NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE));
    }

    @ParameterizedTest(name = "{0}에서 {1}")
    @MethodSource("invalidTransitions")
    void 허용되지_않은_상태_전이는_실패한다(ShipmentStatus current, Transition transition) {
        // given
        Shipment sut = shipmentIn(current);

        // when & then
        assertThatThrownBy(() -> transition.action.accept(sut))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.INVALID_SHIPMENT_STATE));
        assertThat(sut.getStatus()).isEqualTo(current);
    }

    static Stream<Arguments> invalidTransitions() {
        return Arrays.stream(ShipmentStatus.values())
                .flatMap(status -> Arrays.stream(Transition.values())
                        .filter(transition -> !ALLOWED.getOrDefault(status, Set.of()).contains(transition))
                        .map(transition -> Arguments.of(status, transition)));
    }

    @Test
    void 상품_준비_중에는_배송지를_변경할_수_있다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);

        // when
        sut.changeAddress(otherAddress());

        // then
        assertThat(sut.getAddress()).isEqualTo(otherAddress());
    }

    @ParameterizedTest
    @MethodSource("statusesExceptPreparing")
    void 상품_준비가_아니면_배송지를_변경할_수_없다(ShipmentStatus status) {
        // given
        Shipment sut = shipmentIn(status);

        // when & then
        assertThatThrownBy(() -> sut.changeAddress(otherAddress()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.ADDRESS_CHANGE_NOT_ALLOWED));
    }

    @Test
    void 배송지_변경_요청은_현재_배송지를_변경_전_배송지로_남긴다() {
        // given
        Shipment sut = withId(shipmentIn(ShipmentStatus.PREPARING), 1L);

        // when
        ShipmentAddressChangeRequest request = sut.requestAddressChange(otherAddress(), 3_000, NOW);

        // then
        assertThat(request).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(request.getShipmentId()).isEqualTo(1L);
            softly.assertThat(request.getStatus()).isEqualTo(AddressChangeStatus.PENDING);
            softly.assertThat(request.getRequestedAddress()).isEqualTo(otherAddress());
            softly.assertThat(request.getPreviousAddress()).isEqualTo(address());
            softly.assertThat(request.getAdditionalFeeNotice()).isEqualTo(3_000);
            softly.assertThat(request.getRequestedAt()).isEqualTo(NOW);
            softly.assertThat(request.getProcessedAt()).isNull();
            softly.assertThat(sut.getAddress()).isEqualTo(address());
        });
    }

    @ParameterizedTest
    @MethodSource("추가_운임_안내_금액")
    void 추가_운임_안내_금액은_결제_때_낸_추가_운임과의_차액이다(int paidSurcharge, int requestedSurcharge, int expected) {
        // given
        Shipment shipment = withId(validBuilder().regionSurcharge(paidSurcharge).build(), 1L);

        // when
        ShipmentAddressChangeRequest sut = shipment.requestAddressChange(otherAddress(), requestedSurcharge, NOW);

        // then
        assertThat(sut.getAdditionalFeeNotice()).isEqualTo(expected);
    }

    static Stream<Arguments> 추가_운임_안내_금액() {
        return Stream.of(
                Arguments.of(0, 3_000, 3_000),
                Arguments.of(3_000, 5_000, 2_000),
                Arguments.of(3_000, 3_000, 0),
                Arguments.of(5_000, 0, 0)
        );
    }

    @Test
    void 요청_배송지의_추가_운임은_음수일_수_없다() {
        // given
        Shipment shipment = withId(shipmentIn(ShipmentStatus.PREPARING), 1L);

        // when & then
        assertThatThrownBy(() -> shipment.requestAddressChange(otherAddress(), -1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("추가 운임은 0 이상이어야 합니다.");
    }

    @ParameterizedTest
    @MethodSource("statusesExceptPreparing")
    void 상품_준비가_아니면_배송지_변경을_요청할_수_없다(ShipmentStatus status) {
        // given
        Shipment sut = withId(shipmentIn(status), 1L);

        // when & then
        assertThatThrownBy(() -> sut.requestAddressChange(otherAddress(), 0, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DeliveryErrorCode.ADDRESS_CHANGE_NOT_ALLOWED));
    }

    static Stream<ShipmentStatus> statusesExceptPreparing() {
        return EnumSet.complementOf(EnumSet.of(ShipmentStatus.PREPARING)).stream();
    }

    @Test
    void 시각은_인자로_받은_값을_기록한다() {
        // given
        Shipment sut = shipmentIn(ShipmentStatus.PREPARING);
        LocalDateTime shippedAt = NOW.plusHours(3);

        // when
        sut.dispatch(Carrier.CJ_LOGISTICS, "1234567890", shippedAt);

        // then
        assertThat(sut.getShippedAt()).isEqualTo(shippedAt);
    }
}
