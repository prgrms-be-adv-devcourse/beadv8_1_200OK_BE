package com.ok.delivery.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class DeliveryOutboxTest {

    @Test
    void 발송_실패를_기록하면_시도_횟수가_늘고_상태는_PENDING으로_남는다() {
        // given
        DeliveryOutbox sut = DeliveryOutbox.pending(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{}");

        // when
        sut.recordFailure("브로커 거절");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(DeliveryOutboxStatus.PENDING);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getLastError()).isEqualTo("브로커 거절");
        });
    }

    @Test
    void 실패_원인이_500자를_넘으면_잘라서_남긴다() {
        // given: 그대로 저장하면 컬럼 길이를 넘어 실패 기록이 매번 실패한다
        DeliveryOutbox sut = DeliveryOutbox.pending(DeliveryOutboxEventType.SHIPMENT_DISPATCHED, "2", "{}");

        // when
        sut.recordFailure("가".repeat(DeliveryOutbox.MAX_ERROR_LENGTH + 1));

        // then
        assertThat(sut.getLastError()).hasSize(DeliveryOutbox.MAX_ERROR_LENGTH);
    }
}
