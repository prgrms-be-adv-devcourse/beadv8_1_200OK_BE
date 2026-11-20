package com.ok.refund.adapter.in.event;

import com.ok.refund.adapter.in.event.dto.OrderRefundRevertFailedMessage;
import com.ok.refund.adapter.in.event.dto.OrderRefundRevertedMessage;
import com.ok.refund.application.port.in.OrderRefundRevertFailedCommand;
import com.ok.refund.application.port.in.OrderRefundRevertedCommand;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 단위 테스트: 주문 되돌리기 결과(#622)의 값 검사와 입력 포트 호출 (Kafka·Spring 없이 메서드를 직접 부른다)
@ExtendWith(MockitoExtension.class)
class RefundOrderKafkaListenerTest {

    private static final String EMPTY_IDS = "[REFUND_ORDER_RESULT_INVALID] refundId, orderId, orderItemId는 비어 있을 수 없습니다.";

    @Mock
    private RefundSagaUseCase useCase;

    @InjectMocks
    private RefundOrderKafkaListener sut;

    @Test
    void 정상_되돌림_메시지는_값을_그대로_입력_포트에_넘긴다() {
        // when
        sut.onOrderRefundReverted(new OrderRefundRevertedMessage(11L, 22L, 33L));

        // then: 같은 타입 필드가 여러 개라 필드마다 다른 값으로 순서를 확인한다
        ArgumentCaptor<OrderRefundRevertedCommand> captor = ArgumentCaptor.forClass(OrderRefundRevertedCommand.class);
        then(useCase).should().recordOrderRefundReverted(captor.capture());
        OrderRefundRevertedCommand command = captor.getValue();
        assertThat(command).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(command.refundId()).isEqualTo(11L);
            softly.assertThat(command.orderId()).isEqualTo(22L);
            softly.assertThat(command.orderItemId()).isEqualTo(33L);
        });
    }

    @Test
    void 정상_실패_메시지는_이유까지_그대로_입력_포트에_넘긴다() {
        // when
        sut.onOrderRefundRevertFailed(new OrderRefundRevertFailedMessage(11L, 22L, 33L, "ALREADY_CONFIRMED"));

        // then
        ArgumentCaptor<OrderRefundRevertFailedCommand> captor = ArgumentCaptor.forClass(OrderRefundRevertFailedCommand.class);
        then(useCase).should().recordOrderRefundRevertFailure(captor.capture());
        OrderRefundRevertFailedCommand command = captor.getValue();
        assertThat(command).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(command.refundId()).isEqualTo(11L);
            softly.assertThat(command.orderId()).isEqualTo(22L);
            softly.assertThat(command.orderItemId()).isEqualTo(33L);
            softly.assertThat(command.reason()).isEqualTo("ALREADY_CONFIRMED");
        });
    }

    @ParameterizedTest
    @MethodSource("ID가_빈_되돌림_메시지")
    void ID가_비면_입력_포트를_부르지_않고_거절한다(OrderRefundRevertedMessage message) {
        // when & then
        assertThatThrownBy(() -> sut.onOrderRefundReverted(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(EMPTY_IDS + " message=" + message);
        then(useCase).should(never()).recordOrderRefundReverted(any());
    }

    static Stream<OrderRefundRevertedMessage> ID가_빈_되돌림_메시지() {
        return Stream.of(
                new OrderRefundRevertedMessage(null, 22L, 33L),
                new OrderRefundRevertedMessage(11L, null, 33L),
                new OrderRefundRevertedMessage(11L, 22L, null));
    }

    @ParameterizedTest
    @MethodSource("빈_이유")
    void 실패_이유가_비면_입력_포트를_부르지_않고_거절한다(String reason) {
        // given
        OrderRefundRevertFailedMessage message = new OrderRefundRevertFailedMessage(11L, 22L, 33L, reason);

        // when & then
        assertThatThrownBy(() -> sut.onOrderRefundRevertFailed(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("[REFUND_ORDER_RESULT_INVALID] 실패 이유가 비어 있습니다. message=" + message);
        then(useCase).should(never()).recordOrderRefundRevertFailure(any());
    }

    static Stream<String> 빈_이유() {
        return Stream.of(null, "", " ");
    }

    // 되돌리기 줄의 이유 칸(last_reason)이 100자라, 넘는 값을 조용히 잘라 받지 않고 입구에서 거절한다
    @Test
    void 실패_이유가_100자를_넘으면_거절한다() {
        // given
        OrderRefundRevertFailedMessage message = new OrderRefundRevertFailedMessage(11L, 22L, 33L, "A".repeat(101));

        // when & then
        assertThatThrownBy(() -> sut.onOrderRefundRevertFailed(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("[REFUND_ORDER_RESULT_INVALID] 실패 이유가 100자를 넘습니다. length=101, refundId=11");
    }
}
