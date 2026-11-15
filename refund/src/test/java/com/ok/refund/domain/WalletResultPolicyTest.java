package com.ok.refund.domain;

import com.ok.refund.domain.WalletResultPolicy.Decision;
import com.ok.refund.domain.WalletResultPolicy.Kind;
import com.ok.refund.domain.WalletResultPolicy.SkipReason;
import com.ok.refund.domain.WalletResultPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 예시: 환불 7번(주문 1000번, 구매자 2000번, 20,000원). 번호는 Long 캐시(-128~127) 밖이라 ==로 잘못 비교하면 걸린다
class WalletResultPolicyTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 1_000L;
    private static final Long BUYER_ID = 2_000L;
    private static final long AMOUNT = 20_000L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 15, 10, 0);

    @ParameterizedTest
    @MethodSource("지갑_결과_종류")
    void 환불이_없으면_넘긴다(Kind kind) {
        // when
        Verdict sut = WalletResultPolicy.judge(kind, null, null, null, ORDER_ID, BUYER_ID, AMOUNT, null);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.REFUND_NOT_FOUND));
    }

    static Stream<Kind> 지갑_결과_종류() {
        return Stream.of(Kind.COMPLETED, Kind.FAILED);
    }

    @ParameterizedTest
    @MethodSource("지갑_결과_종류")
    void 이미_완료된_환불이면_넘긴다(Kind kind) {
        // when
        Verdict sut = WalletResultPolicy.judge(kind, refund(RefundStatus.COMPLETED), null, null, ORDER_ID, BUYER_ID, AMOUNT, "R");

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.ALREADY_COMPLETED));
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닌_상태")
    void 처리_중이_아닌_환불이면_넘긴다(RefundStatus status) {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.COMPLETED, refund(status), null, null, ORDER_ID, BUYER_ID, AMOUNT, null);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.NOT_PROCESSING));
    }

    static Stream<RefundStatus> 처리_중이_아닌_상태() {
        return Stream.of(RefundStatus.REQUESTED, RefundStatus.REJECTED, RefundStatus.WITHDRAWN);
    }

    // 금액이 달라도 불일치보다 재고 단계 확인이 먼저다. 지갑 완료가 반영된 환불에 실패·불일치를 남기면 앞선 완료와 모순이다
    @Test
    void 재고_단계가_시작된_환불의_실패는_값이_달라도_넘긴다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.FAILED, refund(RefundStatus.PROCESSING), awaiting(), started(),
                ORDER_ID, BUYER_ID, AMOUNT + 1, "PAYMENT_NOT_FOUND");

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.AFTER_WALLET_SUCCESS));
    }

    // 재고가 실패해 할 차례로 남은 환불에 같은 완료가 다시 오면 재고를 다시 시도해야 한다
    @Test
    void 재고_단계가_시작된_환불이라도_값이_같은_완료는_받는다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.COMPLETED, refund(RefundStatus.PROCESSING), succeeded(RefundStepType.WALLET),
                started(), ORDER_ID, BUYER_ID, AMOUNT, null);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    @Test
    void 재고_단계가_시작된_환불에_값이_다른_완료가_오면_넘긴다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.COMPLETED, refund(RefundStatus.PROCESSING), succeeded(RefundStepType.WALLET),
                started(), ORDER_ID, BUYER_ID, AMOUNT + 1, null);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.AFTER_WALLET_SUCCESS));
    }

    @ParameterizedTest
    @MethodSource("어긋난_값")
    void 주문이나_구매자나_금액이_다르면_불일치다(Kind kind, Long orderId, Long buyerId, long amount) {
        // when
        Verdict sut = WalletResultPolicy.judge(kind, refund(RefundStatus.PROCESSING), awaiting(), null,
                orderId, buyerId, amount, "PAYMENT_NOT_FOUND");

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.MISMATCH, null));
    }

    static Stream<Arguments> 어긋난_값() {
        return Stream.of(Kind.values()).flatMap(kind -> Stream.of(
                Arguments.of(kind, 1_001L, BUYER_ID, AMOUNT),
                Arguments.of(kind, ORDER_ID, 2_001L, AMOUNT),
                Arguments.of(kind, ORDER_ID, BUYER_ID, AMOUNT - 1)));
    }

    // Long을 ==로 비교하면 캐시 밖 같은 값을 다르다고 본다. 새 객체로 만들어 값으로 비교하는지 본다
    @Test
    void 번호는_객체가_달라도_값이_같으면_같다고_본다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.COMPLETED, refund(RefundStatus.PROCESSING), awaiting(), null,
                Long.valueOf(1_000L), Long.valueOf(2_000L), AMOUNT, null);

        // then
        assertThat(sut.decision()).isEqualTo(Decision.ACCEPT);
    }

    @Test
    void 같은_이유로_이미_멈춘_지갑_줄에_같은_실패가_오면_넘긴다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.FAILED, refund(RefundStatus.PROCESSING), stopped("PAYMENT_NOT_FOUND"), null,
                ORDER_ID, BUYER_ID, AMOUNT, "PAYMENT_NOT_FOUND");

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.SAME_FAILURE));
    }

    // 정책은 받기까지만 정한다. 이미 다른 이유로 멈춘 줄의 처음 이유를 지키는 일은 지휘자가 줄을 고칠 때 한다
    @Test
    void 다른_이유로_멈춘_지갑_줄에_실패가_오면_받는다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.FAILED, refund(RefundStatus.PROCESSING), stopped("RESULT_MISMATCH"), null,
                ORDER_ID, BUYER_ID, AMOUNT, "PAYMENT_NOT_FOUND");

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    // #544 결정 8: 앞선 불일치로 지갑 줄이 멈춰 있어도 맞는 완료가 오면 받는다(지갑 줄은 멈춤 그대로, 환불은 완료로 간다)
    @Test
    void 불일치로_멈춘_지갑_줄에_맞는_완료가_오면_받는다() {
        // when
        Verdict sut = WalletResultPolicy.judge(Kind.COMPLETED, refund(RefundStatus.PROCESSING), stopped("RESULT_MISMATCH"), null,
                ORDER_ID, BUYER_ID, AMOUNT, null);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, 20_000L,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep awaiting() {
        return RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1, NOW.plusMinutes(30), null, NOW);
    }

    private static RefundStep stopped(String reason) {
        return RefundStep.restore(2L, REFUND_ID, RefundStepType.WALLET, RefundStepStatus.STOPPED, 1, null, reason, NOW);
    }

    private static RefundStep succeeded(RefundStepType type) {
        return RefundStep.restore(2L, REFUND_ID, type, RefundStepStatus.SUCCEEDED, 1, null, null, NOW);
    }

    private static RefundStep started() {
        return RefundStep.restore(3L, REFUND_ID, RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS, 1, NOW.plusMinutes(1), null, NOW);
    }
}
