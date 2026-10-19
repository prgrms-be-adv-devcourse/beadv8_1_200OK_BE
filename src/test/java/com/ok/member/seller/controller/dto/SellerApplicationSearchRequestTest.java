package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationSearchQuery;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class SellerApplicationSearchRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 조회_조건을_생략하면_모든_상태를_0페이지_20건으로_조회한다() {
        // when
        SellerApplicationSearchRequest sut = new SellerApplicationSearchRequest(null, null, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isNull();
            softly.assertThat(sut.page()).isEqualTo(0);
            softly.assertThat(sut.size()).isEqualTo(20);
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_상태")
    void 상태를_비워_보내면_생략한_것과_같게_모든_상태를_조회한다(String status) {
        // when
        SellerApplicationSearchRequest sut = new SellerApplicationSearchRequest(status, 0, 20);

        // then
        assertThat(sut.status()).isNull();
    }

    static Stream<String> 비어_있는_상태() {
        return Stream.of("", " ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "IN_REVIEW", "APPROVED", "REJECTED"})
    void 신청_상태_이름은_허용한다(String status) {
        // when
        Set<ConstraintViolation<SellerApplicationSearchRequest>> violations =
                validator.validate(new SellerApplicationSearchRequest(status, 0, 20));

        // then
        assertThat(violations).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "WAITING", "PENDING,APPROVED"})
    void 신청_상태_이름이_아니면_검증에서_거절한다(String status) {
        // when
        Set<ConstraintViolation<SellerApplicationSearchRequest>> violations =
                validator.validate(new SellerApplicationSearchRequest(status, 0, 20));

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("status");
    }

    @Test
    void 요청을_조회_조건으로_바꿀_때_필드가_뒤바뀌지_않는다() {
        // given
        SellerApplicationSearchRequest request = new SellerApplicationSearchRequest(" IN_REVIEW ", 2, 50);

        // when
        SellerApplicationSearchQuery sut = request.toQuery();

        // then
        assertThat(sut).isEqualTo(new SellerApplicationSearchQuery("IN_REVIEW", 2, 50));
    }

}
