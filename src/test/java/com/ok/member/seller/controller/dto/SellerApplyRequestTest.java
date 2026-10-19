package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplyCommand;
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

class SellerApplyRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @MethodSource("같은_사업자등록번호의_여러_표기")
    void 사업자등록번호는_표기와_관계없이_하이픈_형식으로_맞춘다(String businessNumber) {
        // when
        SellerApplyRequest sut = request(businessNumber);

        // then
        assertThat(sut.businessNumber()).isEqualTo("512-81-04273");
    }

    static Stream<String> 같은_사업자등록번호의_여러_표기() {
        return Stream.of("512-81-04273", "5128104273", " 512 81 04273 ", "512-8104273");
    }

    @ParameterizedTest
    @MethodSource("잘못된_사업자등록번호")
    void 숫자_10자리가_아닌_사업자등록번호는_검증에서_거절한다(String businessNumber) {
        // when
        Set<ConstraintViolation<SellerApplyRequest>> violations = validator.validate(request(businessNumber));

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("businessNumber");
    }

    static Stream<String> 잘못된_사업자등록번호() {
        return Stream.of("512-81-0427", "51281042731", "512-81-0427a", "", " ", null);
    }

    @Test
    void 요청을_신청_입력으로_바꿀_때_필드가_뒤바뀌지_않는다() {
        // given
        SellerApplyRequest request = new SellerApplyRequest(
                "  청송 햇살농원 ", " 김영수 ", "5128104273", " 제2026-경북청송-0031호 ");

        // when
        SellerApplyCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.storeName()).isEqualTo("청송 햇살농원");
            softly.assertThat(sut.representativeName()).isEqualTo("김영수");
            softly.assertThat(sut.businessNumber()).isEqualTo("512-81-04273");
            softly.assertThat(sut.mailOrderNumber()).isEqualTo("제2026-경북청송-0031호");
        });
    }

    private static SellerApplyRequest request(String businessNumber) {
        return new SellerApplyRequest("청송 햇살농원", "김영수", businessNumber, "제2026-경북청송-0031호");
    }

}
