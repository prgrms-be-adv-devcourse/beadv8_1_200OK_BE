package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutSearchQuery;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutSearchRequestTest {

    private static final LocalDateTime JAN_1 = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime OCT_1 = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final LocalDateTime NOV_1 = LocalDateTime.of(2026, 11, 1, 0, 0);

    // 기간 검증에 관련된 검증 항목 이름 (필드 2개의 @NotNull, 검증 메서드 2개의 @AssertTrue)
    private static final List<String> PERIOD_PROPERTIES = List.of("from", "to", "periodValid", "periodWithinMaxRange");

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
    void 페이지_번호와_크기를_생략하면_0과_20으로_채운다() {
        // when
        PayoutSearchRequest sut = new PayoutSearchRequest(null, null, OCT_1, NOV_1, null, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.page()).isEqualTo(0);
            softly.assertThat(sut.size()).isEqualTo(20);
        });
    }

    @Test
    void 조회_조건을_조회_Query로_그대로_옮긴다() {
        // given: page와 size에 일부러 다른 숫자(2, 30)를 넣음. 같은 숫자면 둘이 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        //        from과 to도 같은 이유로 다른 날짜를 넣음
        PayoutSearchRequest request = new PayoutSearchRequest(7L, false, OCT_1, NOV_1, 2, 30);

        // when
        PayoutSearchQuery sut = request.toQuery();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.payeeId()).isEqualTo(7L);
            softly.assertThat(sut.system()).isFalse();
            softly.assertThat(sut.from()).isEqualTo(OCT_1);
            softly.assertThat(sut.to()).isEqualTo(NOV_1);
            softly.assertThat(sut.page()).isEqualTo(2);
            softly.assertThat(sut.size()).isEqualTo(30);
        });
    }

    @Test
    void 시작이_종료보다_앞이고_1년_이내면_기간_검증을_통과한다() {
        // given: 10월 1일 ~ 11월 1일
        PayoutSearchRequest request = periodRequest(OCT_1, NOV_1);

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void 시작과_종료가_같으면_거절한다() {
        // given: from 포함·to 미포함이라 같은 시각이면 결과가 항상 0건
        PayoutSearchRequest request = periodRequest(OCT_1, OCT_1);

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).containsExactly("periodValid");
    }

    @Test
    void 시작이_종료보다_늦으면_거절한다() {
        // given: 11월 1일 ~ 10월 1일
        PayoutSearchRequest request = periodRequest(NOV_1, OCT_1);

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).containsExactly("periodValid");
    }

    @Test
    void 시작_시각이_없으면_필수값으로만_거절한다() {
        // given
        PayoutSearchRequest request = periodRequest(null, NOV_1);

        // when
        List<String> violations = periodViolations(request);

        // then: 순서·길이 검증까지 함께 걸리면 오류 메시지가 겹치므로 필수값 오류 하나만 나와야 함
        assertThat(violations).containsExactly("from");
    }

    @Test
    void 종료_시각이_없으면_필수값으로만_거절한다() {
        // given
        PayoutSearchRequest request = periodRequest(OCT_1, null);

        // when
        List<String> violations = periodViolations(request);

        // then: 순서·길이 검증까지 함께 걸리면 오류 메시지가 겹치므로 필수값 오류 하나만 나와야 함
        assertThat(violations).containsExactly("to");
    }

    @Test
    void 시작과_종료가_모두_없으면_둘_다_필수값으로_거절한다() {
        // given
        PayoutSearchRequest request = periodRequest(null, null);

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).containsExactlyInAnyOrder("from", "to");
    }

    @Test
    void 기간이_정확히_1년이면_통과한다() {
        // given: 1월 1일 ~ 다음 해 1월 1일 (to는 미포함이라 실제로는 1년치)
        PayoutSearchRequest request = periodRequest(JAN_1, JAN_1.plusYears(1));

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).isEmpty();
    }

    @Test
    void 기간이_1년을_넘으면_거절한다() {
        // given: 1년에서 1초 초과
        PayoutSearchRequest request = periodRequest(JAN_1, JAN_1.plusYears(1).plusSeconds(1));

        // when
        List<String> violations = periodViolations(request);

        // then
        assertThat(violations).containsExactly("periodWithinMaxRange");
    }

    // 기간만 바꾼 요청 (나머지는 생략값)
    private PayoutSearchRequest periodRequest(LocalDateTime from, LocalDateTime to) {
        return new PayoutSearchRequest(null, null, from, to, null, null);
    }

    // 검증 결과 중 기간 관련 항목 이름만 모음 (필드 2개의 @NotNull, 검증 메서드 2개의 @AssertTrue)
    private List<String> periodViolations(PayoutSearchRequest request) {
        List<String> names = new ArrayList<>();
        for (ConstraintViolation<PayoutSearchRequest> violation : validator.validate(request)) {
            String name = violation.getPropertyPath().toString();
            if (PERIOD_PROPERTIES.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
