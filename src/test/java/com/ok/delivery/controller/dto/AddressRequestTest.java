package com.ok.delivery.controller.dto;

import com.ok.delivery.domain.Address;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class AddressRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static AddressRequest request(String phone, String postalCode) {
        return new AddressRequest(
                "홍길동",
                phone,
                postalCode,
                "서울시 강남구 테헤란로 1",
                "101동 202호",
                "문 앞에 놓아주세요"
        );
    }

    private static Set<String> violatedFields(AddressRequest request) {
        Set<ConstraintViolation<AddressRequest>> violations = validator.validate(request);
        return violations.stream().map(
                v -> v.getPropertyPath().toString()
                ).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void 정상_요청은_검증을_통과한다() {
        // given
        AddressRequest sut = request("010-1234-5678", "06236");

        // when
        Set<ConstraintViolation<AddressRequest>> violations = validator.validate(sut);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void 배송_요청사항이_없어도_검증을_통과한다() {
        // given
        AddressRequest sut = new AddressRequest("홍길동", "01012345678", "06236", "주소", "상세", null);

        // when
        Set<ConstraintViolation<AddressRequest>> violations = validator.validate(sut);

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("validPhones")
    void 연락처_숫자_10_11자리는_통과한다(String phone) {
        // given
        AddressRequest sut = request(phone, "06236");

        // when
        Set<String> violatedFields = violatedFields(sut);

        // then
        assertThat(violatedFields).isEmpty();
    }

    static Stream<String> validPhones() {
        return Stream.of("0101234567", "01012345678", "010-123-4567", "010-1234-5678");
    }

    @ParameterizedTest
    @MethodSource("invalidPhones")
    void 연락처_9자리_12자리_또는_허용되지_않는_문자는_거부한다(String phone) {
        // given
        AddressRequest sut = request(phone, "06236");

        // when
        Set<String> violatedFields = violatedFields(sut);

        // then
        assertThat(violatedFields).containsExactly("recipientPhone");
    }

    static Stream<String> invalidPhones() {
        return Stream.of(
                "010123456", "010123456789", "010-abcd-5678", "-01012345678",
                "010.1234.5678", "010_1234_5678", "(010)1234-5678", "010#1234#5678", "010!12345678"
        );
    }

    @Test
    void 우편번호_5자리는_통과한다() {
        // given
        AddressRequest sut = request("01012345678", "06236");

        // when
        Set<String> violatedFields = violatedFields(sut);

        // then
        assertThat(violatedFields).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidPostalCodes")
    void 우편번호_4자리_6자리_또는_숫자가_아니면_거부한다(String postalCode) {
        // given
        AddressRequest sut = request("01012345678", postalCode);

        // when
        Set<String> violatedFields = violatedFields(sut);

        // then
        assertThat(violatedFields).containsExactly("postalCode");
    }

    static Stream<String> invalidPostalCodes() {
        return Stream.of("0623", "062361", "0623a");
    }

    @ParameterizedTest
    @MethodSource("blankValues")
    void 필수_항목이_비어_있으면_모두_거부한다(String blank) {
        // given
        AddressRequest sut = new AddressRequest(blank, blank, blank, blank, blank, null);

        // when
        Set<String> violatedFields = violatedFields(sut);

        // then
        assertThat(violatedFields).containsExactlyInAnyOrder(
                "recipientName", "recipientPhone", "postalCode", "address", "addressDetail");
    }

    static Stream<String> blankValues() {
        return Stream.of(null, "", "  ");
    }

    @Test
    void 요청을_Address_값_객체로_변환하면_연락처는_숫자만_남는다() {
        // given
        AddressRequest sut = request("010-1234-5678", "06236");

        // when
        Address address = sut.toAddress();

        // then
        assertThat(address).isNotNull();
        assertSoftly(
                softly -> {
                    softly.assertThat(address.getRecipientName()).isEqualTo("홍길동");
                    softly.assertThat(address.getRecipientPhone()).isEqualTo("01012345678");
                    softly.assertThat(address.getPostalCode()).isEqualTo("06236");
                    softly.assertThat(address.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
                    softly.assertThat(address.getAddressDetail()).isEqualTo("101동 202호");
                    softly.assertThat(address.getDeliveryNote()).isEqualTo("문 앞에 놓아주세요");
                }
        );
    }
}
