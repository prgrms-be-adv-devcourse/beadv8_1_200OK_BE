package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerApplicationRejectCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SellerApplicationRejectRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 거절_사유의_앞뒤_공백을_지우고_거절_입력으로_바꾼다() {
        // given
        SellerApplicationRejectRequest request = new SellerApplicationRejectRequest("  신고번호를 확인할 수 없습니다. ");

        // when
        SellerApplicationRejectCommand sut = request.toCommand();

        // then
        assertThat(sut.reason()).isEqualTo("신고번호를 확인할 수 없습니다.");
    }

    @ParameterizedTest
    @MethodSource("잘못된_거절_사유")
    void 거절_사유가_비었거나_500자를_넘으면_검증에서_거절한다(String reason) {
        // when
        Set<ConstraintViolation<SellerApplicationRejectRequest>> violations =
                validator.validate(new SellerApplicationRejectRequest(reason));

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("reason");
    }

    static Stream<String> 잘못된_거절_사유() {
        return Stream.of(null, "", " ", "가".repeat(501));
    }

    @Test
    void 공백을_지운_뒤_500자면_허용한다() {
        // when: 앞뒤 공백까지 세면 502자지만 지운 뒤 500자다
        Set<ConstraintViolation<SellerApplicationRejectRequest>> violations =
                validator.validate(new SellerApplicationRejectRequest(" " + "가".repeat(500) + " "));

        // then
        assertThat(violations).isEmpty();
    }

}
