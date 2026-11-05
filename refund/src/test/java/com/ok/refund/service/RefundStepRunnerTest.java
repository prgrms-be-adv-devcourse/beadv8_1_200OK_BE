package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.RefundFailedStep;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

// 기록 여부와 기록한 값이 이 클래스의 요구사항이라 서비스 호출을 검증한다
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RefundStepRunnerTest {

    private static final Long REFUND_ID = 7L;

    @Mock
    RefundService refundService;
    @InjectMocks
    RefundStepRunner sut;

    @Test
    void 성공하면_결과를_돌려주고_기록하지_않는다() {
        // when
        String result = sut.run(REFUND_ID, RefundFailedStep.WALLET, () -> "OK");

        // then
        assertThat(result).isEqualTo("OK");
        then(refundService).should(never()).recordFailure(any(), any(), any());
    }

    @Test
    void 실패하면_단계와_에러_코드를_기록하고_원래_예외를_던진다() {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);

        // when & then
        assertThatThrownBy(() -> sut.run(REFUND_ID, RefundFailedStep.ORDER, () -> {
            throw failure;
        })).isSameAs(failure);
        then(refundService).should().recordFailure(REFUND_ID, RefundFailedStep.ORDER, "ORDER_API_FAILED");
    }

    // 예외 메시지에는 다른 모듈의 응답 내용이 들어갈 수 있어 메시지가 아니라 클래스 이름만 남는지 본다
    @Test
    void 에러_코드가_없는_예외면_클래스_이름을_기록한다() {
        // given
        IllegalStateException failure = new IllegalStateException("상대 서버 응답 본문");

        // when & then
        assertThatThrownBy(() -> sut.run(REFUND_ID, RefundFailedStep.PRODUCT, () -> {
            throw failure;
        })).isSameAs(failure);
        then(refundService).should().recordFailure(REFUND_ID, RefundFailedStep.PRODUCT, "IllegalStateException");
    }

    // 기록 실패로 응답을 바꾸면 진짜 원인(다른 모듈 실패)이 가려진다. 원래 예외를 던지고 기록 실패는 거기에 붙어 있어야 한다
    @Test
    void 기록마저_실패하면_원래_예외에_붙여_던진다() {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        CannotAcquireLockException recordError = new CannotAcquireLockException("잠금 대기 시간 초과");
        willThrow(recordError).given(refundService).recordFailure(REFUND_ID, RefundFailedStep.WALLET, "ORDER_API_FAILED");

        // when & then
        assertThatThrownBy(() -> sut.run(REFUND_ID, RefundFailedStep.WALLET, () -> {
            throw failure;
        })).isSameAs(failure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(recordError));
    }

    // 원래 예외가 RestApiException이면 ApiControllerAdvice는 로그를 남기지 않아, 이 ERROR 로그가 기록 실패의 유일한 흔적이다.
    // 운영자가 refundId로 찾을 수 있어야 하므로 식별자가 들어간 줄이 실제로 찍히는지 본다
    @Test
    void 기록마저_실패하면_환불_ID와_단계를_ERROR로_남긴다(CapturedOutput output) {
        // given
        RestApiException failure = new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        willThrow(new CannotAcquireLockException("잠금 대기 시간 초과"))
                .given(refundService).recordFailure(REFUND_ID, RefundFailedStep.ORDER, "ORDER_API_FAILED");

        // when
        assertThatThrownBy(() -> sut.run(REFUND_ID, RefundFailedStep.ORDER, () -> {
            throw failure;
        })).isSameAs(failure);

        // then
        assertThat(output.getOut()).contains(
                "ERROR", "환불 실패 단계 기록 실패. 멈춘 단계가 환불에 남지 않음. refundId=7, step=ORDER, failureCode=ORDER_API_FAILED");
    }
}
