package com.ok.wallet.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class WalletMessageFailureTest {
    private static final String TOPIC = "payout.seller-payout-created";

    @Test
    void 기록하면_대기_상태로_남는다() {
        // when
        WalletMessageFailure sut = WalletMessageFailure.record(TOPIC, 0, 10L, "12", "{}", "amount 없음");

        // then
        assertThat(sut.getStatus()).isEqualTo(WalletMessageFailureStatus.PENDING);
    }

    @Test
    void 키와_실패_이유가_칸보다_길면_잘라서_남긴다() {
        // given
        String longKey = "k".repeat(WalletMessageFailure.MAX_MESSAGE_KEY_LENGTH + 1);
        String longReason = "r".repeat(WalletMessageFailure.MAX_FAILURE_REASON_LENGTH + 1);

        // when
        WalletMessageFailure sut = WalletMessageFailure.record(TOPIC, 0, 10L, longKey, null, longReason);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMessageKey()).hasSize(WalletMessageFailure.MAX_MESSAGE_KEY_LENGTH);
            softly.assertThat(sut.getFailureReason()).hasSize(WalletMessageFailure.MAX_FAILURE_REASON_LENGTH);
        });
    }

    @Test
    void 키와_원문이_없어도_남긴다() {
        // when
        WalletMessageFailure sut = WalletMessageFailure.record(TOPIC, 0, 10L, null, null, "JSON을 읽을 수 없음");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMessageKey()).isNull();
            softly.assertThat(sut.getPayload()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_값")
    void 토픽이_없으면_남기지_않는다(String topic) {
        // when & then
        assertThatThrownBy(() -> WalletMessageFailure.record(topic, 0, 10L, null, null, "이유"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("topic은 필수입니다.");
    }

    static Stream<String> 비어_있는_값() {
        return Stream.of(null, "", " ");
    }

    @ParameterizedTest
    @MethodSource("비어_있는_값")
    void 실패_이유가_없으면_남기지_않는다(String failureReason) {
        // when & then
        assertThatThrownBy(() -> WalletMessageFailure.record(TOPIC, 0, 10L, null, null, failureReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("failureReason은 필수입니다.");
    }
}
