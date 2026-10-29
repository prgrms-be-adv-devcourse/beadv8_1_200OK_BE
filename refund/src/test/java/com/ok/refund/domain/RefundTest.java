package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.NOW;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundTest {

    @Test
    void 요청하면_요청됨이고_상품_금액만_정해지며_진행_중_칸이_채워진다() {
        // when
        Refund sut = requested(2);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getShippingFeeAmount()).isZero();
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(sut.getRefundAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    @Test
    void 개수가_1_미만이면_요청할_수_없다() {
        // when & then
        assertThatThrownBy(() -> requested(0))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_QUANTITY));
    }

    @Test
    void 철회하면_철회로_끝나고_진행_중_칸을_비운다() {
        // given
        Refund sut = requested(1);

        // when
        sut.withdraw(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.WITHDRAWN);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 거절하면_사유를_남기고_진행_중_칸을_비운다() {
        // given
        Refund sut = requested(1);

        // when
        sut.reject("이미 포장했습니다", NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(sut.getRejectReason()).isEqualTo("이미 포장했습니다");
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_거절_사유")
    void 거절_사유가_비어_있으면_거절할_수_없다(String reason) {
        // given
        Refund refund = requested(1);

        // when & then
        assertThatThrownBy(() -> refund.reject(reason, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REJECT_REASON_REQUIRED));
    }

    static Stream<String> 비어_있는_거절_사유() {
        return Stream.of(null, "", " ");
    }

    @Test
    void 처리를_시작하면_배송비와_추가_운임을_저장하고_진행_중_칸은_유지한다() {
        // given
        Refund sut = requested(3);

        // when
        sut.startProcessing(3_000L, 3_000L, NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(sut.getRefundAmount()).isEqualTo(36_000L);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    @Test
    void 완료하면_완료_시각을_남기고_앞서_기록한_실패를_지운다() {
        // given
        Refund sut = requested(1);
        sut.startProcessing(0L, 0L, NOW);
        sut.recordFailure(RefundFailedStep.WALLET, "WALLET_API_FAILED");

        // when
        sut.complete(NOW.plusMinutes(1));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(NOW.plusMinutes(1));
            softly.assertThat(sut.getFailedStep()).isNull();
            softly.assertThat(sut.getFailureCode()).isNull();
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @Test
    void 실패를_기록해도_처리_중으로_남는다() {
        // given
        Refund sut = requested(1);
        sut.startProcessing(0L, 0L, NOW);

        // when
        sut.recordFailure(RefundFailedStep.ORDER, "ORDER_API_FAILED");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundFailedStep.ORDER);
            softly.assertThat(sut.getFailureCode()).isEqualTo("ORDER_API_FAILED");
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    @Test
    void 실패_코드가_칸보다_길면_잘라_저장한다() {
        // given
        Refund sut = requested(1);
        sut.startProcessing(0L, 0L, NOW);

        // when
        sut.recordFailure(RefundFailedStep.WALLET, "X".repeat(150));

        // then
        assertThat(sut.getFailureCode()).hasSize(100);
    }

    @Test
    void 승인_불가로_끝나면_진행_중_칸을_비운다() {
        // given
        Refund sut = requested(1);
        sut.startProcessing(0L, 0L, NOW);

        // when
        sut.markNotApprovable(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.NOT_APPROVABLE);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("요청됨이_아닐_때_막히는_행동")
    void 요청됨이_아니면_철회_거절_승인을_할_수_없다(Consumer<Refund> action) {
        // given
        Refund refund = requested(1);
        refund.startProcessing(0L, 0L, NOW);

        // when & then
        assertThatThrownBy(() -> action.accept(refund))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    static Stream<Consumer<Refund>> 요청됨이_아닐_때_막히는_행동() {
        return Stream.of(
                refund -> refund.withdraw(NOW),
                refund -> refund.reject("사유", NOW),
                refund -> refund.startProcessing(0L, 0L, NOW));
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닐_때_막히는_행동")
    void 처리_중이_아니면_완료_승인_불가_실패_기록을_할_수_없다(Consumer<Refund> action) {
        // given
        Refund refund = requested(1);

        // when & then
        assertThatThrownBy(() -> action.accept(refund))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    static Stream<Consumer<Refund>> 처리_중이_아닐_때_막히는_행동() {
        return Stream.of(
                refund -> refund.complete(NOW),
                refund -> refund.markNotApprovable(NOW),
                refund -> refund.recordFailure(RefundFailedStep.WALLET, "X"));
    }

    @Test
    void 요청한_구매자만_구매자_주인이다() {
        // given
        Refund sut = requested(1);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.isOwnedByBuyer(BUYER_ID)).isTrue();
            softly.assertThat(sut.isOwnedByBuyer(999L)).isFalse();
        });
    }

    @Test
    void 주문에서_받은_판매자만_판매자_주인이다() {
        // given
        Refund sut = requested(1);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.isOwnedBySeller(SELLER_ID)).isTrue();
            softly.assertThat(sut.isOwnedBySeller(999L)).isFalse();
        });
    }
}
