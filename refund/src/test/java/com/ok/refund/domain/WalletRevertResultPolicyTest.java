package com.ok.refund.domain;

import com.ok.refund.domain.WalletRevertResultPolicy.Decision;
import com.ok.refund.domain.WalletRevertResultPolicy.SkipReason;
import com.ok.refund.domain.WalletRevertResultPolicy.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// 예시: 환불 7번. 주문 1000번, 구매자 2000번에게 20,000원을 돌려줬는데 재고가 거절돼 그 돈을 다시 가져오는 중이다(#546)
class WalletRevertResultPolicyTest {

    private static final Long REFUND_ID = 7L;
    private static final Long ORDER_ID = 1_000L;
    private static final Long BUYER_ID = 2_000L;
    private static final long AMOUNT = 20_000L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 10, 0);

    @Test
    void 처리_중이고_회수_결과를_기다리며_값이_같으면_받는다() {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), revert(RefundStepStatus.AWAITING_RESULT),
                ORDER_ID, BUYER_ID, AMOUNT);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("어긋난_값")
    void 주문_구매자_금액_중_하나라도_다르면_불일치다(String 경우, Long orderId, Long buyerId, long amount) {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), revert(RefundStepStatus.AWAITING_RESULT),
                orderId, buyerId, amount);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.MISMATCH, null));
    }

    static Stream<Arguments> 어긋난_값() {
        return Stream.of(
                Arguments.of("주문이 다름", 1_001L, BUYER_ID, AMOUNT),
                Arguments.of("구매자가 다름", ORDER_ID, 2_001L, AMOUNT),
                Arguments.of("금액이 다름", ORDER_ID, BUYER_ID, 19_000L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("넘기는_경우")
    void 받을_곳이_없으면_넘긴다(String 경우, Refund refund, RefundStep revert, SkipReason expected) {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund, revert, ORDER_ID, BUYER_ID, AMOUNT);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, expected));
    }

    static Stream<Arguments> 넘기는_경우() {
        return Stream.of(
                Arguments.of("환불 없음", null, null, SkipReason.REFUND_NOT_FOUND),
                Arguments.of("처리 중 아님", refund(RefundStatus.COMPLETED), revert(RefundStepStatus.AWAITING_RESULT), SkipReason.NOT_PROCESSING),
                Arguments.of("회수 줄 없음", refund(RefundStatus.PROCESSING), null, SkipReason.NO_REVERT_STEP),
                Arguments.of("회수 줄 이미 성공", refund(RefundStatus.PROCESSING), revert(RefundStepStatus.SUCCEEDED), SkipReason.ALREADY_FINISHED),
                Arguments.of("회수 줄 이미 멈춤", refund(RefundStatus.PROCESSING), revert(RefundStepStatus.STOPPED), SkipReason.ALREADY_FINISHED));
    }

    @Test
    void 끝난_회수_줄은_값이_달라도_불일치가_아니라_넘긴다() {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), revert(RefundStepStatus.SUCCEEDED),
                ORDER_ID, BUYER_ID, 1L);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.SKIP, SkipReason.ALREADY_FINISHED));
    }

    // #572: 지갑이 "구매자 2001번에게 19,000원 돌려줬다"고 알려 그 돈을 다시 가져오는 회수다. 지갑은 자기 기록(2001번, 19,000원)으로
    // 가져오고 결과에도 그 값을 넣으므로, 환불 값(2000번, 20,000원)이 아니라 회수 줄에 남긴 값과 같으면 받는다
    @Test
    void 지갑이_알린_값으로_시작한_회수는_그_값과_같으면_받는다() {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), revertOfMismatch(2_001L, 19_000L),
                ORDER_ID, 2_001L, 19_000L);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.ACCEPT, null));
    }

    // 회수 줄에 남긴 값과 다르면 환불 값과 같아도 불일치다. 환불 값과 같다는 것은 지갑이 처음과 다른 기록으로 가져왔다는 뜻이라 믿을 수 없다
    @ParameterizedTest(name = "{0}")
    @MethodSource("지갑이_알린_값과_어긋난_값")
    void 지갑이_알린_값으로_시작한_회수는_그_값과_다르면_불일치다(String 경우, Long orderId, Long buyerId, long amount) {
        // when
        Verdict sut = WalletRevertResultPolicy.judge(refund(RefundStatus.PROCESSING), revertOfMismatch(2_001L, 19_000L),
                orderId, buyerId, amount);

        // then
        assertThat(sut).isEqualTo(new Verdict(Decision.MISMATCH, null));
    }

    static Stream<Arguments> 지갑이_알린_값과_어긋난_값() {
        return Stream.of(
                Arguments.of("환불 값과 같음", ORDER_ID, BUYER_ID, AMOUNT),
                Arguments.of("주문이 다름", 1_001L, 2_001L, 19_000L),
                Arguments.of("구매자가 다름", ORDER_ID, 2_002L, 19_000L),
                Arguments.of("금액이 다름", ORDER_ID, 2_001L, 18_000L));
    }

    private static Refund refund(RefundStatus status) {
        return Refund.restore(REFUND_ID, ORDER_ID, 101L, 2, BUYER_ID, 5L, 55L, "감자", "1kg", 10_000L, AMOUNT,
                0L, 0L, 0L, null, status, null, null, null, null, null);
    }

    private static RefundStep revert(RefundStepStatus status) {
        LocalDateTime nextCheckAt = status.isFinished() ? null : NOW.plusMinutes(5);
        return RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, status, 1, nextCheckAt, null, NOW);
    }

    // 결과를 기다리는 회수 줄(#572). Long 캐시 밖 번호를 long으로 받아 새 객체가 되게 한다(구매자 비교가 equals인지 보려는 것)
    private static RefundStep revertOfMismatch(long buyerId, long amount) {
        return RefundStep.restore(4L, REFUND_ID, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT, 1,
                NOW.plusMinutes(5), null, NOW, buyerId, amount, NOW);
    }
}
