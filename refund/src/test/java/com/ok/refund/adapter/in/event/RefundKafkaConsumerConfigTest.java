package com.ok.refund.adapter.in.event;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 단위 테스트: 환불 Kafka 오류 처리 규칙 (Spring·Kafka 없이 설정 메서드만 확인)
class RefundKafkaConsumerConfigTest {

    @Test
    void 값이_틀려_던진_예외는_다시_시도하지_않는다() {
        // given
        DefaultErrorHandler sut = RefundKafkaConsumerConfig.refundErrorHandler();

        // when: removeClassification은 등록돼 있던 분류를 돌려준다 (false = 다시 시도하지 않음, null = 등록 안 됨)
        Boolean retryable = sut.removeClassification(IllegalArgumentException.class);

        // then
        assertThat(retryable).isFalse();
    }

    // 분류 표만 보면 "IllegalArgumentException이 등록됐다"까지만 알 수 있다. 실제로 다시 시도할지는 처리기가 쓰는 판정기(ExceptionMatcher)가 정하므로
    // 그 판정기에 예외를 넣어 본다. 판정기를 꺼내는 메서드가 protected라 리플렉션으로 부른다
    // 일시적 실패(상품 HTTP 실패, DB 끊김)와 그 밖의 예외는 다시 시도해야 한다. 누가 분류를 넓히면(RuntimeException 등) 여기서 걸린다
    @Test
    void 값이_틀린_예외만_넘기고_그_밖의_예외는_다시_시도한다() {
        // given
        ExceptionMatcher sut = ReflectionTestUtils.invokeMethod(RefundKafkaConsumerConfig.refundErrorHandler(), "getExceptionMatcher");

        // when & then (match가 true = 다시 시도한다)
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.match(new IllegalArgumentException("값이 틀림"))).isFalse();
            // 리스너가 던진 예외는 컨테이너가 ListenerExecutionFailedException으로 감싸 넘긴다. 원인까지 보고 판단해야 한다
            softly.assertThat(sut.match(new ListenerExecutionFailedException("감싼 예외", new IllegalArgumentException("값이 틀림"))))
                    .isFalse();
            softly.assertThat(sut.match(new RestApiException(RefundErrorCode.PRODUCT_API_FAILED))).isTrue();
            softly.assertThat(sut.match(new DataAccessResourceFailureException("DB 끊김"))).isTrue();
            softly.assertThat(sut.match(new RuntimeException("그 밖의 예외"))).isTrue();
        });
    }

    @Test
    void 일시적_실패는_1초부터_두_배씩_5번_다시_시도한다() {
        // given
        BackOffExecution sut = RefundKafkaConsumerConfig.refundBackOff().start();

        // when: 다음 대기 시간을 끝(STOP)이 나올 때까지 모은다
        List<Long> intervals = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            intervals.add(sut.nextBackOff());
        }

        // then: 1·2·4·8·16초(합 31초) 뒤 더 시도하지 않는다
        assertThat(intervals).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, BackOffExecution.STOP);
    }
}
