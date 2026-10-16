package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutAdjustmentCreateCommand;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutAdjustmentCreateRequestTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void 조정_사유의_앞뒤_공백을_제거한다() {
        // when
        PayoutAdjustmentCreateRequest sut = request(-2_000L, "  금액 오입력 정정  ");

        // then
        assertThat(sut.reason()).isEqualTo("금액 오입력 정정");
    }

    @Test
    void 조정_사유가_null이면_null을_유지해_필수값_검증에_걸리게_한다() {
        // when
        PayoutAdjustmentCreateRequest sut = request(-2_000L, null);

        // then
        assertThat(sut.reason()).isNull();
    }

    @Test
    void 조정_금액이_0원이면_거절한다() {
        // given: 0원 조정은 장부에 의미 없는 줄만 남김
        PayoutAdjustmentCreateRequest request = request(0L, "금액 오입력 정정");

        // when
        List<String> violations = violations(request);

        // then
        assertThat(violations).containsExactly("amountNotZero");
    }

    @Test
    void 조정_금액이_음수면_차감_조정으로_통과한다() {
        // given
        PayoutAdjustmentCreateRequest request = request(-2_000L, "금액 오입력 정정");

        // when
        List<String> violations = violations(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void 조정_금액이_양수면_통과한다() {
        // given
        PayoutAdjustmentCreateRequest request = request(2_000L, "금액 오입력 정정");

        // when
        List<String> violations = violations(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void 조정_금액이_없으면_0원_검증이_아니라_필수값으로만_거절한다() {
        // given
        PayoutAdjustmentCreateRequest request = request(null, "금액 오입력 정정");

        // when
        List<String> violations = violations(request);

        // then: 0원 검증까지 함께 걸리면 오류 메시지가 겹치므로 필수값 오류 하나만 나와야 함
        assertThat(violations).containsExactly("amount");
    }

    @Test
    void 조정_요청을_조정_Command로_그대로_옮긴다() {
        // given: orderItemId와 amount, eventType과 reason에 일부러 다른 값을 넣음. 같은 값이면 서로 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        PayoutAdjustmentCreateRequest request =
                new PayoutAdjustmentCreateRequest(900L, "SALE_AMOUNT", -2_000L, "금액 오입력 정정");

        // when
        PayoutAdjustmentCreateCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderItemId()).isEqualTo(900L);
            softly.assertThat(sut.eventType()).isEqualTo("SALE_AMOUNT");
            softly.assertThat(sut.amount()).isEqualTo(-2_000L);
            softly.assertThat(sut.reason()).isEqualTo("금액 오입력 정정");
        });
    }

    private PayoutAdjustmentCreateRequest request(Long amount, String reason) {
        return new PayoutAdjustmentCreateRequest(900L, "SALE_AMOUNT", amount, reason);
    }

    // 걸린 검증 항목 이름을 모두 모음 (금액 외 필드는 올바른 값이라, 금액 관련 항목만 나옴)
    private List<String> violations(PayoutAdjustmentCreateRequest request) {
        List<String> names = new ArrayList<>();
        for (ConstraintViolation<PayoutAdjustmentCreateRequest> violation : validator.validate(request)) {
            names.add(violation.getPropertyPath().toString());
        }
        return names;
    }
}
