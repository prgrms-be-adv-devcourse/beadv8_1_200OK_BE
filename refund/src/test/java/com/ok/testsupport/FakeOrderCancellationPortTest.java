package com.ok.testsupport;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.OrderCancellation;
import com.ok.refund.application.port.out.OrderCancellationResult;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트의 주문 쪽 결과는 이 Fake가 정한다. Fake가 실제 주문(#542)과 다르게 답하면 통합 테스트가 틀린 것을 맞다고 하므로
// 실제 주문의 규칙(같은 refundId 다른 내용은 기록 어긋남 422, 이미 반영된 refundId는 출고보다 먼저 봄)을 직접 확인한다(#578, #554)
class FakeOrderCancellationPortTest {

    private final FakeOrderCancellationPort sut = new FakeOrderCancellationPort();

    @Test
    void 같은_refundId로_같은_내용이_다시_오면_더하지_않고_반영됨으로_답한다() {
        // given
        OrderCancellation first = new OrderCancellation(7L, 101L, 1, 0L);
        sut.reflectCancellation(first);

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isFalse();
            softly.assertThat(sut.reflectedOf(7L)).contains(first);
        });
    }

    // 실제 주문은 이 경우를 422로 답하고(주문 #737), 실제 어댑터는 그것을 ORDER_RECORD_CONFLICT로 바꾼다(#554)
    @Test
    void 같은_refundId인데_내용이_다르면_기록_어긋남을_던지고_처음_값을_지킨다() {
        // given
        OrderCancellation first = new OrderCancellation(7L, 101L, 1, 0L);
        sut.reflectCancellation(first);

        // when & then
        assertThatThrownBy(() -> sut.reflectCancellation(new OrderCancellation(7L, 101L, 2, 0L)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_RECORD_CONFLICT));
        assertThat(sut.reflectedOf(7L)).contains(first);
    }

    // 반영된 뒤에 출고돼도 같은 요청을 다시 받으면 반영됨이어야 다시 진행(#576)이 이어 갈 수 있다
    @Test
    void 이미_반영된_refundId는_출고_표시보다_먼저_본다() {
        // given
        sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));
        sut.markShipped(101L);

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result.rejected()).isFalse();
    }

    @Test
    void 처음_오는_요청이_출고_표시한_상품이면_거절하고_반영하지_않는다() {
        // given
        sut.markShipped(101L);

        // when
        OrderCancellationResult result = sut.reflectCancellation(new OrderCancellation(7L, 101L, 1, 0L));

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.rejected()).isTrue();
            softly.assertThat(sut.reflectedOf(7L)).isEmpty();
        });
    }
}
