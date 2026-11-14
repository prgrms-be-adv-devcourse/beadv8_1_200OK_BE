package com.ok.refund.adapter.in.event;

import com.ok.refund.adapter.in.event.dto.WalletRefundCompletedMessage;
import com.ok.refund.adapter.in.event.dto.WalletRefundFailedMessage;
import com.ok.refund.application.port.in.HandleWalletRefundResultUseCase;
import com.ok.refund.application.port.in.WalletRefundCompletedCommand;
import com.ok.refund.application.port.in.WalletRefundFailedCommand;
import com.ok.refund.application.service.RefundStepRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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

// 단위 테스트: 값 검사와 입력 포트 호출 (Kafka·Spring 없이 메서드를 직접 부른다)
@ExtendWith(MockitoExtension.class)
class RefundWalletKafkaListenerTest {

    // 리스너가 값이 틀린 메시지를 거절할 때 쓰는 이유 문구
    private static final String EMPTY_IDS = "refundId, orderId, buyerId는 비어 있을 수 없습니다.";
    private static final String NOT_POSITIVE_AMOUNT = "amount는 1 이상이어야 합니다.";
    private static final String EMPTY_REASON = "실패 이유가 비어 있습니다.";

    @Mock
    private HandleWalletRefundResultUseCase useCase;

    @InjectMocks
    private RefundWalletKafkaListener sut;

    // 입력 어댑터는 서비스 패키지를 볼 수 없어(ArchitectureTest) 상한을 따로 둔다. 이 테스트가 두 값이 같게 지킨다.
    // 단계 줄의 이유 칸(last_reason)이 VARCHAR(100)이라, 어느 한쪽만 바뀌면 긴 이유가 DB 저장에서 깨지거나(어댑터 상한이 더 큼) 멀쩡한 이유가 버려진다(더 작음)
    @Test
    void 어댑터의_이유_길이_상한은_서비스의_실패_코드_길이_상한과_같다() {
        // when & then
        assertThat(RefundWalletKafkaListener.MAX_REASON_LENGTH).isEqualTo(RefundStepRunner.MAX_FAILURE_CODE_LENGTH);
    }

    @Test
    void 정상_완료_메시지는_값을_그대로_입력_포트에_넘긴다() {
        // given
        WalletRefundCompletedMessage message = new WalletRefundCompletedMessage(11L, 22L, 33L, 4_000L);

        // when
        sut.onWalletRefundCompleted(message);

        // then: 같은 타입 필드가 여러 개라 필드마다 다른 값으로 순서를 확인한다
        ArgumentCaptor<WalletRefundCompletedCommand> captor = ArgumentCaptor.forClass(WalletRefundCompletedCommand.class);
        then(useCase).should().completeAfterWalletRefund(captor.capture());
        WalletRefundCompletedCommand command = captor.getValue();
        assertThat(command).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(command.refundId()).isEqualTo(11L);
            softly.assertThat(command.orderId()).isEqualTo(22L);
            softly.assertThat(command.buyerId()).isEqualTo(33L);
            softly.assertThat(command.amount()).isEqualTo(4_000L);
        });
    }

    @Test
    void 정상_실패_메시지는_값을_그대로_입력_포트에_넘긴다() {
        // given
        WalletRefundFailedMessage message = new WalletRefundFailedMessage(11L, 22L, 33L, 4_000L, "WALLET_NOT_FOUND");

        // when
        sut.onWalletRefundFailed(message);

        // then
        ArgumentCaptor<WalletRefundFailedCommand> captor = ArgumentCaptor.forClass(WalletRefundFailedCommand.class);
        then(useCase).should().recordWalletRefundFailure(captor.capture());
        WalletRefundFailedCommand command = captor.getValue();
        assertThat(command).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(command.refundId()).isEqualTo(11L);
            softly.assertThat(command.orderId()).isEqualTo(22L);
            softly.assertThat(command.buyerId()).isEqualTo(33L);
            softly.assertThat(command.amount()).isEqualTo(4_000L);
            softly.assertThat(command.reason()).isEqualTo("WALLET_NOT_FOUND");
        });
    }

    @Test
    void 이유가_100자인_실패_메시지는_받아들인다() {
        // given: 컬럼 길이와 같은 경계값
        WalletRefundFailedMessage message = new WalletRefundFailedMessage(1L, 2L, 3L, 1L, "a".repeat(100));

        // when
        sut.onWalletRefundFailed(message);

        // then
        then(useCase).should().recordWalletRefundFailure(any(WalletRefundFailedCommand.class));
    }

    @ParameterizedTest
    @MethodSource("잘못된_완료_메시지")
    void 값이_잘못된_완료_메시지는_입력_포트를_부르지_않고_거절한다(WalletRefundCompletedMessage message, String expectedMessage) {
        // when & then
        assertThatThrownBy(() -> sut.onWalletRefundCompleted(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
        then(useCase).shouldHaveNoInteractions();
    }

    // 문구까지 맞춰 본다. 종류만 보면 검사 하나가 빠져도 다른 이유의 IllegalArgumentException으로 통과한다
    static Stream<Arguments> 잘못된_완료_메시지() {
        return Stream.of(
                invalid(new WalletRefundCompletedMessage(null, 2L, 3L, 1_000L), EMPTY_IDS),
                invalid(new WalletRefundCompletedMessage(1L, null, 3L, 1_000L), EMPTY_IDS),
                invalid(new WalletRefundCompletedMessage(1L, 2L, null, 1_000L), EMPTY_IDS),
                invalid(new WalletRefundCompletedMessage(1L, 2L, 3L, null), NOT_POSITIVE_AMOUNT),
                invalid(new WalletRefundCompletedMessage(1L, 2L, 3L, 0L), NOT_POSITIVE_AMOUNT),
                invalid(new WalletRefundCompletedMessage(1L, 2L, 3L, -1L), NOT_POSITIVE_AMOUNT));
    }

    @ParameterizedTest
    @MethodSource("잘못된_실패_메시지")
    void 값이_잘못된_실패_메시지는_입력_포트를_부르지_않고_거절한다(WalletRefundFailedMessage message, String expectedMessage) {
        // when & then
        assertThatThrownBy(() -> sut.onWalletRefundFailed(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(expectedMessage);
        then(useCase).shouldHaveNoInteractions();
    }

    static Stream<Arguments> 잘못된_실패_메시지() {
        return Stream.of(
                invalid(new WalletRefundFailedMessage(null, 2L, 3L, 1_000L, "X"), EMPTY_IDS),
                invalid(new WalletRefundFailedMessage(1L, null, 3L, 1_000L, "X"), EMPTY_IDS),
                invalid(new WalletRefundFailedMessage(1L, 2L, null, 1_000L, "X"), EMPTY_IDS),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, null, "X"), NOT_POSITIVE_AMOUNT),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, 0L, "X"), NOT_POSITIVE_AMOUNT),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, -1L, "X"), NOT_POSITIVE_AMOUNT),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, 1_000L, null), EMPTY_REASON),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, 1_000L, ""), EMPTY_REASON),
                invalid(new WalletRefundFailedMessage(1L, 2L, 3L, 1_000L, "   "), EMPTY_REASON),
                // 긴 이유는 로그가 길어지지 않게 메시지 대신 길이와 refundId만 남긴다
                Arguments.of(new WalletRefundFailedMessage(1L, 2L, 3L, 1_000L, "a".repeat(101)),
                        "[REFUND_WALLET_RESULT_INVALID] 실패 이유가 100자를 넘습니다. length=101, refundId=1"));
    }

    // 리스너는 거절 문구 끝에 받은 메시지를 그대로 붙인다(넘긴 메시지를 로그에서 찾으려고)
    private static Arguments invalid(Object message, String reason) {
        return Arguments.of(message, "[REFUND_WALLET_RESULT_INVALID] " + reason + " message=" + message);
    }
}
