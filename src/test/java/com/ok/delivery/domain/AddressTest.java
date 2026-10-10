package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class AddressTest {

    private static Address address(String phone, String postalCode) {
        return Address.of(
                "홍길동",
                phone,
                postalCode,
                "서울시 강남구 테헤란로 1",
                "101동 202호",
                "문 앞에 놓아주세요"
        );
    }

    @Test
    void 정상값으로_배송지를_생성한다() {
        // given
        String recipientName = "홍길동";
        String recipientPhone = "01012345678";
        String postalCode = "06236";
        String address = "서울시 강남구 테헤란로 1";
        String addressDetail = "101동 202호";
        String deliveryNote = "문 앞에 놓아주세요";

        // when
        Address sut = Address.of(recipientName, recipientPhone, postalCode, address, addressDetail, deliveryNote);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(
                softly -> {
                    softly.assertThat(sut.getRecipientName()).isEqualTo(recipientName);
                    softly.assertThat(sut.getRecipientPhone()).isEqualTo(recipientPhone);
                    softly.assertThat(sut.getPostalCode()).isEqualTo(postalCode);
                    softly.assertThat(sut.getAddress()).isEqualTo(address);
                    softly.assertThat(sut.getAddressDetail()).isEqualTo(addressDetail);
                    softly.assertThat(sut.getDeliveryNote()).isEqualTo(deliveryNote);
                }
        );
    }

    @Test
    void 하이픈이_포함된_연락처는_숫자만_저장된다() {
        // given
        String phoneWithHyphen = "010-1234-5678";

        // when
        Address sut = address(phoneWithHyphen, "06236");

        // then
        assertThat(sut.getRecipientPhone()).isEqualTo("01012345678");
    }

    @Test
    void 배송_요청사항은_선택값이다() {
        // given
        String deliveryNote = null;

        // when
        Address sut = Address.of("홍길동", "01012345678", "06236", "서울시 강남구 테헤란로 1", "101동 202호", deliveryNote);

        // then
        assertThat(sut.getDeliveryNote()).isNull();
    }

    @ParameterizedTest
    @MethodSource("validPhones")
    void 연락처는_숫자_10자리와_11자리를_허용한다(String phone) {
        // given
        String postalCode = "06236";

        // when
        Address sut = address(phone, postalCode);

        // then
        assertThat(sut.getRecipientPhone()).hasSizeBetween(10, 11).containsOnlyDigits();
    }

    static Stream<String> validPhones() {
        return Stream.of("0101234567", "01012345678", "010-123-4567", "010-1234-5678");
    }

    @ParameterizedTest
    @MethodSource("phonesWithInvalidLength")
    void 연락처는_숫자_9자리와_12자리를_거부한다(String phone) {
        // given
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> address(phone, postalCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("연락처는 숫자 10~11자리여야 합니다.");
    }

    static Stream<String> phonesWithInvalidLength() {
        return Stream.of("010123456", "010123456789", "010-1234-56789");
    }

    @ParameterizedTest
    @MethodSource("phonesWithInvalidCharacters")
    void 연락처에_숫자와_하이픈이_아닌_문자가_있으면_거부한다(String phone) {
        // given
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> address(phone, postalCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("연락처는 숫자 10~11자리여야 합니다.");
    }

    static Stream<String> phonesWithInvalidCharacters() {
        return Stream.of(
                "010-abcd-5678", "010 1234 5678", "+821012345678",
                "010.1234.5678", "010_1234_5678", "(010)1234-5678", "010#1234#5678", "010!12345678"
        );
    }

    @Test
    void 우편번호는_숫자_5자리를_허용한다() {
        // given
        String postalCode = "06236";

        // when
        Address sut = address("01012345678", postalCode);

        // then
        assertThat(sut.getPostalCode()).isEqualTo(postalCode);
    }

    @ParameterizedTest
    @MethodSource("invalidPostalCodes")
    void 우편번호는_숫자_5자리가_아니면_거부한다(String postalCode) {
        // given
        String phone = "01012345678";

        // when & then
        assertThatThrownBy(() -> address(phone, postalCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("우편번호는 숫자 5자리여야 합니다.");
    }

    static Stream<String> invalidPostalCodes() {
        return Stream.of("0623", "062361", "0623a", "06-36");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 수령인이_비어_있으면_거부한다(String recipientName) {
        // given
        String phone = "01012345678";
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> Address.of(recipientName, phone, postalCode, "주소", "상세", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("수령인은 필수입니다.");
    }

    // 필수 필드 5개 테스트가 함께 사용한다.
    static Stream<String> blankValues() {
        return Stream.of(null, "", "  ");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 연락처가_비어_있으면_거부한다(String phone) {
        // given
        String recipientName = "홍길동";
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> Address.of(recipientName, phone, postalCode, "주소", "상세", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("연락처는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 우편번호가_비어_있으면_거부한다(String postalCode) {
        // given
        String recipientName = "홍길동";
        String phone = "01012345678";

        // when & then
        assertThatThrownBy(() -> Address.of(recipientName, phone, postalCode, "주소", "상세", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("우편번호는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 주소가_비어_있으면_거부한다(String address) {
        // given
        String recipientName = "홍길동";
        String phone = "01012345678";
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> Address.of(recipientName, phone, postalCode, address, "상세", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주소는 필수입니다.");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 상세_주소가_비어_있으면_거부한다(String addressDetail) {
        // given
        String recipientName = "홍길동";
        String phone = "01012345678";
        String postalCode = "06236";

        // when & then
        assertThatThrownBy(() -> Address.of(recipientName, phone, postalCode, "주소", addressDetail, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상세 주소는 필수입니다.");
    }

    @Test
    void 같은_값의_배송지는_동등하다() {
        // given
        Address sut = address("010-1234-5678", "06236");
        Address other = address("01012345678", "06236");

        // when
        boolean equal = sut.equals(other);

        // then
        assertThat(equal).isTrue();
    }
}
