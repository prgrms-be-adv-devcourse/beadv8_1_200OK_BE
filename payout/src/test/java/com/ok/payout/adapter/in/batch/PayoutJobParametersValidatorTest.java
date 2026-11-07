package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 단위 테스트: Job 시작 전 필수 파라미터 검사의 분기만 확인 (#485)
// 실제 JobOperator에서 예외가 그대로 나오고 실행 기록이 남지 않는지는 PayoutSettleIntegrationTest에서 확인함
class PayoutJobParametersValidatorTest {

    private static final LocalDateTime REQUESTED_AT = LocalDateTime.of(2026, 11, 7, 2, 0);

    private final PayoutJobParametersValidator sut = new PayoutJobParametersValidator();

    @Test
    void requestedAt이_없으면_MISSING_PAYOUT_REQUESTED_AT_예외가_난다() {
        // given
        JobParameters params = new JobParametersBuilder()
                .addString("runType", "MANUAL", false)
                .toJobParameters();

        // when & then
        assertThatThrownBy(() -> sut.validate(params))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_PAYOUT_REQUESTED_AT));
    }

    @ParameterizedTest
    @MethodSource("잘못된_runType_파라미터")
    void runType이_없거나_비었거나_모르는_값이면_INVALID_PAYOUT_RUN_TYPE_예외가_난다(JobParameters params) {
        // when & then
        assertThatThrownBy(() -> sut.validate(params))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.INVALID_PAYOUT_RUN_TYPE));
    }

    static Stream<Arguments> 잘못된_runType_파라미터() {
        return Stream.of(
                Arguments.of(new JobParametersBuilder().addLocalDateTime("requestedAt", REQUESTED_AT).toJobParameters()),
                Arguments.of(withRunType("")),
                Arguments.of(withRunType(" ")),
                Arguments.of(withRunType("SKIPWAIT")),
                // 소문자·앞뒤 공백은 모르는 값이다 (봐주면 PayoutRunType.valueOf에서 터짐)
                Arguments.of(withRunType("manual")),
                Arguments.of(withRunType(" MANUAL")),
                Arguments.of(withRunType("MANUAL "))
        );
    }

    @ParameterizedTest
    @MethodSource("올바른_runType")
    void 파라미터가_올바르면_예외가_나지_않는다(String runType) {
        // when & then
        assertThatCode(() -> sut.validate(withRunType(runType))).doesNotThrowAnyException();
    }

    static Stream<String> 올바른_runType() {
        return Stream.of("SCHEDULED", "MANUAL", "SKIP_WAIT");
    }

    private static JobParameters withRunType(String runType) {
        return new JobParametersBuilder()
                .addLocalDateTime("requestedAt", REQUESTED_AT)
                .addString("runType", runType, false)
                .toJobParameters();
    }
}
