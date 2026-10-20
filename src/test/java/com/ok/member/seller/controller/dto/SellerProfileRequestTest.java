package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerProfileCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerProfileRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @MethodSource("비어_있는_선택_값")
    void 비어_있는_상점_소개와_사진_URL은_없는_값으로_바꾼다(String value) {
        // when
        SellerProfileRequest sut = new SellerProfileRequest("청송 햇살농원", "김영수", "054-873-2210", value, value);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.introduction()).isNull();
            softly.assertThat(sut.profileImageUrl()).isNull();
        });
    }

    static Stream<String> 비어_있는_선택_값() {
        return Stream.of("", " ", null);
    }

    @ParameterizedTest
    @MethodSource("허용하는_연락처")
    void 지역번호_휴대폰_대표번호_형식의_연락처를_허용한다(String phone) {
        // when
        Set<ConstraintViolation<SellerProfileRequest>> violations = validator.validate(request(phone));

        // then
        assertThat(violations).isEmpty();
    }

    static Stream<String> 허용하는_연락처() {
        return Stream.of("054-873-2210", "02-123-4567", "010-1234-5678", "1588-0000");
    }

    @ParameterizedTest
    @MethodSource("허용하지_않는_연락처")
    void 형식이_맞지_않는_연락처는_검증에서_거절한다(String phone) {
        // when
        Set<ConstraintViolation<SellerProfileRequest>> violations = validator.validate(request(phone));

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("customerServicePhone");
    }

    static Stream<String> 허용하지_않는_연락처() {
        return Stream.of("0548732210", "054-873-221", "고객센터", "", " ", null);
    }

    @Test
    void 요청을_프로필_입력으로_바꿀_때_필드가_뒤바뀌지_않는다() {
        // given
        SellerProfileRequest request = new SellerProfileRequest(
                " 청송 햇살농원 ", " 김영수 ", " 054-873-2210 ", " 3대째 사과만 키웁니다. ", " https://example.com/a.jpg ");

        // when
        SellerProfileCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.storeName()).isEqualTo("청송 햇살농원");
            softly.assertThat(sut.representativeName()).isEqualTo("김영수");
            softly.assertThat(sut.customerServicePhone()).isEqualTo("054-873-2210");
            softly.assertThat(sut.introduction()).isEqualTo("3대째 사과만 키웁니다.");
            softly.assertThat(sut.profileImageUrl()).isEqualTo("https://example.com/a.jpg");
        });
    }

    private static SellerProfileRequest request(String phone) {
        return new SellerProfileRequest("청송 햇살농원", "김영수", phone, null, null);
    }

}
