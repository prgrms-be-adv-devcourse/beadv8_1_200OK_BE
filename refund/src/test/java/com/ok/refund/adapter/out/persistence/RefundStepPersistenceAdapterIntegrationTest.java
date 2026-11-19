package com.ok.refund.adapter.out.persistence;

import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundSagaPlan;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.domain.WalletReport;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 단계 진행 표(#574)의 저장 규칙을 실제 DB로 확인한다. "환불 행을 잠그지 않고 단계 행을 쓰면 막는다"와
// "같은 단계를 두 번 만들면 유일 제약으로 실패한다"는 서비스 경로로 일부러 만들 수 없어 어댑터를 직접 본다.
// 어댑터는 트랜잭션을 열지 않으므로 서비스처럼 TransactionTemplate 안에서 부른다
class RefundStepPersistenceAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 10, 0);

    @Autowired
    RefundStepPersistencePort refundStepPersistencePort;
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    RefundStepJpaRepository refundStepJpaRepository;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    PlatformTransactionManager transactionManager;

    private Long refundId;

    @BeforeEach
    void setUp() {
        refundId = inTransaction(() -> refundPersistencePort.saveNew(
                Refund.request(70L, 701L, 1, 1L, 7L, 77L, "상품", "옵션", 10_000L, null))).getId();
    }

    // 단계 행이 환불 행을 외래키로 가리키므로 단계 행을 먼저 지운다
    @AfterEach
    void tearDown() {
        refundStepJpaRepository.deleteAllInBatch();
        refundJpaRepository.deleteAllInBatch();
    }

    // 알람(#576)이 다시 볼 환불만 고르는지 본다: 할 차례·시도 중·결과 대기(#577)이면서 다시 볼 시각이 지난 줄. 끝 상태·아직 이른 줄은 빼고,
    // 한 환불에 해당 줄이 둘이어도 한 번만, 가장 오래 기다린 순으로 돌려준다
    @Test
    void 다시_볼_시각이_지난_할_차례와_시도_중과_결과_대기_줄이_있는_환불만_오래_기다린_순으로_고른다() {
        // given
        Long waitedLong = newRefundWith(801L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.READY, NOW.minusMinutes(5)),
                step(RefundStepType.PRODUCT, RefundStepStatus.READY, NOW.minusMinutes(3)));
        Long expiredInProgress = newRefundWith(802L, step(RefundStepType.ORDER, RefundStepStatus.IN_PROGRESS, NOW.minusMinutes(1)));
        Long dueExactlyNow = newRefundWith(803L, step(RefundStepType.ORDER, RefundStepStatus.READY, NOW));
        newRefundWith(804L, step(RefundStepType.ORDER, RefundStepStatus.READY, NOW.plusSeconds(1)));
        Long overdueAwaiting = newRefundWith(805L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, NOW.minusMinutes(30)));
        newRefundWith(808L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, NOW.plusMinutes(1)));
        newRefundWith(806L, step(RefundStepType.ORDER, RefundStepStatus.STOPPED, null));
        // 처리 중이 아닌 환불(요청됨)은 줄이 할 차례·시각 지남이어도 고르지 않는다. 다시 진행할 일이 없고 줄에 쓰지도 않아 매 회차 다시 뽑힌다
        newRefundWith(807L, false, step(RefundStepType.ORDER, RefundStepStatus.READY, NOW.minusMinutes(10)));

        // when
        List<Long> sut = refundStepPersistencePort.findRefundIdsDueForResume(NOW, 10);

        // then
        assertThat(sut).containsExactly(overdueAwaiting, waitedLong, expiredInProgress, dueExactlyNow);
    }

    // 멈춘 환불 찾기(#577)가 사람이 볼 처리 중 환불만 고르는지 본다. 고르는 넷: 멈춘 줄, 줄 없음, 앞 단계 성공 + 다음 줄 없음, 오래된 지갑 결과 대기.
    // 결과 대기가 오래됐는지는 결과를 처음 기다리기 시작한 시각(awaiting_since)으로 본다. 기준은 "지금(10:00)보다 30분 전"이다
    @Test
    void 사람이_봐야_하는_처리_중_환불만_고른다() {
        // given
        Long stopped = newRefundWith(821L, step(RefundStepType.ORDER, RefundStepStatus.STOPPED, null));
        Long noSteps = newRefundWith(822L);
        Long noStepAfterOrder = newRefundWith(823L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null));
        Long noStepAfterWallet = newRefundWith(824L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, null));
        Long awaitingLong = newRefundWith(825L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                waitingWallet(RefundStepStatus.AWAITING_RESULT, NOW.minusMinutes(31)));
        // 다시 보내는 중이라 잠깐 시도 중인 지갑 줄도 결과 대기와 같이 본다
        Long resendingLong = newRefundWith(826L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                waitingWallet(RefundStepStatus.IN_PROGRESS, NOW.minusMinutes(30)));
        // 고르지 않음: 결과를 기다린 지 29분, 할 차례(알람이 다시 시도 중), 재고 시도 중, 끝난 환불의 멈춘 줄, 처리 중이 아닌 환불
        newRefundWith(830L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                waitingWallet(RefundStepStatus.AWAITING_RESULT, NOW.minusMinutes(29)));
        newRefundWith(827L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.READY, NOW));
        newRefundWith(828L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.PRODUCT, RefundStepStatus.IN_PROGRESS, NOW));
        newRefundWith(829L, false, step(RefundStepType.ORDER, RefundStepStatus.STOPPED, null));

        // when
        List<Long> sut = refundStepPersistencePort.findRefundIdsNeedingAttention(NOW.minusMinutes(30));

        // then
        assertThat(sut).containsExactly(stopped, noSteps, noStepAfterOrder, noStepAfterWallet, awaitingLong, resendingLong);
    }

    // 줄은 오래전에 만들었어도(예: 처음 발행이 여러 번 실패) 결과를 기다리기 시작한 지 오래되지 않았으면 고르지 않는다.
    // 만든 시각(created_at)은 저장할 때 실제 시각으로 채워지므로, 기준을 실제 시각보다 하루 뒤로 두어 만든 시각만 보면 고르게 만든다
    @Test
    void 지갑_결과를_기다린_시간은_줄을_만든_시각이_아니라_처음_기다린_시각으로_잰다() {
        // given
        LocalDateTime threshold = LocalDateTime.now().plusDays(1);
        newRefundWith(831L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                waitingWallet(RefundStepStatus.AWAITING_RESULT, threshold.plusMinutes(1)));

        // when
        List<Long> sut = refundStepPersistencePort.findRefundIdsNeedingAttention(threshold);

        // then
        assertThat(sut).isEmpty();
    }

    // 발행 뒤 "결과 대기" 기록이 실패해 시도 중으로만 남은 지갑 줄은 처음 기다린 시각이 없다. 그때는 줄을 만든 시각으로 잰다
    @Test
    void 처음_기다린_시각이_없는_지갑_줄은_만든_시각으로_잰다() {
        // given
        Long resendingWithoutStart = newRefundWith(832L, step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                step(RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS, NOW.plusMinutes(2)));

        // when
        List<Long> picked = refundStepPersistencePort.findRefundIdsNeedingAttention(LocalDateTime.now().plusDays(1));
        List<Long> notYet = refundStepPersistencePort.findRefundIdsNeedingAttention(LocalDateTime.now().minusDays(1));

        // then
        assertThat(picked).containsExactly(resendingWithoutStart);
        assertThat(notYet).isEmpty();
    }

    // "다음 줄 없음"·"멈춘 줄"·"줄 없음" 규칙은 계획(RefundSagaPlan.fromSteps)과 찾기 쿼리 두 곳에 있다(판단 4).
    // 한쪽만 고쳐 어긋나지 않게, 같은 단계 줄 조합에서 계획이 멈춤을 정하는지와 쿼리가 고르는지가 같은지 본다
    @ParameterizedTest(name = "{0}")
    @MethodSource("계획과_찾기가_같아야_하는_단계_줄")
    void 계획이_멈춤을_정하는_환불과_찾기가_고르는_환불이_같다(String 경우, List<RefundStep> steps, boolean expected) {
        // given
        Long id = newRefundWith(841L, steps.toArray(RefundStep[]::new));
        Refund refund = inTransaction(() -> refundPersistencePort.findById(id)).orElseThrow();
        List<RefundStep> saved = inTransaction(() -> refundStepPersistencePort.findAllByRefundId(id));

        // when
        boolean planStops = RefundSagaPlan.fromSteps(refund, saved, NOW) == RefundSagaPlan.Action.STOP;
        boolean picked = refundStepPersistencePort.findRefundIdsNeedingAttention(NOW.minusMinutes(30)).contains(id);

        // then
        assertSoftly(softly -> {
            softly.assertThat(planStops).isEqualTo(expected);
            softly.assertThat(picked).isEqualTo(expected);
        });
    }

    static Stream<Arguments> 계획과_찾기가_같아야_하는_단계_줄() {
        return Stream.of(
                Arguments.of("줄 없음 → 둘 다 멈춤", List.of(), true),
                Arguments.of("주문 멈춤 → 둘 다 멈춤", List.of(step(RefundStepType.ORDER, RefundStepStatus.STOPPED, null)), true),
                Arguments.of("주문 성공, 다음 줄 없음 → 둘 다 멈춤",
                        List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null)), true),
                Arguments.of("지갑 성공, 재고 줄 없음 → 둘 다 멈춤",
                        List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, null)), true),
                Arguments.of("지갑 할 차례 → 둘 다 아님",
                        List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                                step(RefundStepType.WALLET, RefundStepStatus.READY, NOW.plusMinutes(1))), false),
                Arguments.of("지갑 결과 대기 1분째 → 둘 다 아님",
                        List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                                waitingWallet(RefundStepStatus.AWAITING_RESULT, NOW.minusMinutes(1))), false),
                Arguments.of("재고 할 차례 → 둘 다 아님",
                        List.of(step(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                                step(RefundStepType.WALLET, RefundStepStatus.SUCCEEDED, null),
                                step(RefundStepType.PRODUCT, RefundStepStatus.READY, NOW.plusMinutes(1))), false));
    }

    @Test
    void 다시_볼_환불은_정한_개수까지만_고른다() {
        // given
        Long first = newRefundWith(811L, step(RefundStepType.ORDER, RefundStepStatus.READY, NOW.minusMinutes(2)));
        newRefundWith(812L, step(RefundStepType.ORDER, RefundStepStatus.READY, NOW.minusMinutes(1)));

        // when
        List<Long> sut = refundStepPersistencePort.findRefundIdsDueForResume(NOW, 1);

        // then
        assertThat(sut).containsExactly(first);
    }

    // 주문·주문 상품·옵션 번호를 모두 바꿔 서로 겹치지 않는 처리 중 환불을 하나 더 만들고 주어진 단계 줄을 붙인다
    private Long newRefundWith(Long orderItemId, RefundStep... steps) {
        return newRefundWith(orderItemId, true, steps);
    }

    private Long newRefundWith(Long orderItemId, boolean processing, RefundStep... steps) {
        return inTransaction(() -> {
            Refund refund = Refund.request(orderItemId, orderItemId, 1, 1L, 7L, orderItemId, "상품", "옵션", 10_000L, null);
            if (processing) {
                refund.approve(NOW, RefundShippingFee.none());
            }
            Long id = refundPersistencePort.saveNew(refund).getId();
            refundPersistencePort.findByIdForUpdate(id);
            for (RefundStep step : steps) {
                refundStepPersistencePort.saveNew(RefundStep.restore(null, id, step.getType(), step.getStatus(),
                        step.getAttemptCount(), step.getNextCheckAt(), step.getLastReason(), step.getAwaitingSince(), NOW));
            }
            return id;
        });
    }

    // 결과를 기다리는 지갑 줄(다시 보내는 중이면 시도 중). awaitingSince는 처음 기다리기 시작한 시각
    private static RefundStep waitingWallet(RefundStepStatus status, LocalDateTime awaitingSince) {
        return RefundStep.restore(null, null, RefundStepType.WALLET, status, 1, NOW.plusMinutes(2), null, awaitingSince, NOW);
    }

    private static RefundStep step(RefundStepType type, RefundStepStatus status, LocalDateTime nextCheckAt) {
        String reason = status == RefundStepStatus.STOPPED ? "멈춤" : null;
        return RefundStep.restore(null, null, type, status, 1, nextCheckAt, reason, NOW);
    }

    @Test
    void 단계_줄을_저장하고_다시_읽는다() {
        // given
        RefundStep saved = inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep order = refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
            refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.WALLET, NOW));
            return order;
        });

        // when
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getId()).isNotNull();
            softly.assertThat(saved.getCreatedAt()).isNotNull();
            softly.assertThat(sut.getId()).isEqualTo(saved.getId());
            softly.assertThat(sut.getRefundId()).isEqualTo(refundId);
            softly.assertThat(sut.getType()).isEqualTo(RefundStepType.ORDER);
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(2));
            softly.assertThat(sut.getLastReason()).isNull();
        });
        List<RefundStep> all = inTransaction(() -> refundStepPersistencePort.findAllByRefundId(refundId));
        assertThat(all).extracting(RefundStep::getType).containsExactly(RefundStepType.ORDER, RefundStepType.WALLET);
    }

    // 처음 기다리기 시작한 시각(#577)이 수정 저장으로 들어가고 다시 읽힌다(매퍼가 바뀌는 칸에 넣었는지)
    @Test
    void 결과_대기로_바꾸면_처음_기다린_시각이_저장된다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.WALLET, NOW));
        });

        // when
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
            wallet.awaitResult(NOW.plusSeconds(3));
            refundStepPersistencePort.update(wallet);
            return wallet;
        });

        // then
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET)).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(sut.getAwaitingSince()).isEqualTo(NOW.plusSeconds(3));
        });
    }

    // 지갑이 보낸 값(#761)이 수정 저장으로 들어가고 칸마다 제자리로 다시 읽힌다. 주문·구매자는 서로 다른 값을 써서 뒤바뀌면 걸리게 한다.
    // 실패 메시지 값으로 봐서 실패 이유 칸까지 확인한다. 마이그레이션의 CHECK도 이 저장으로 함께 지나간다
    @Test
    void 결과_불일치로_멈춘_지갑_줄의_지갑_값이_저장되고_다시_읽힌다() {
        // given
        WalletReport report = WalletReport.failed(1_000L, 6L, 9_000L, "PAYMENT_NOT_FOUND");
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.WALLET, NOW));
        });

        // when
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
            wallet.stopForMismatch(report);
            refundStepPersistencePort.update(wallet);
            return wallet;
        });

        // then
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET)).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(sut.getLastReason()).isEqualTo(RefundStep.RESULT_MISMATCH);
            softly.assertThat(sut.getWalletReport()).isEqualTo(report);
        });
    }

    // 값이 있는 줄을 다시 읽어 그대로 update해도 값이 남는다. 매퍼가 수정 때 값 칸을 옮기지 않으면 NULL로 덮이는데,
    // CHECK는 다섯 칸이 함께 비는 것을 막지 않아 조용히 사라진다(#761 검토)
    @Test
    void 지갑_값이_있는_줄을_다시_update해도_값이_사라지지_않는다() {
        // given
        WalletReport report = WalletReport.completed(1_000L, 6L, 10_000L);
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep wallet = RefundStep.start(refundId, RefundStepType.WALLET, NOW);
            wallet.stopForMismatch(report);
            return refundStepPersistencePort.saveNew(wallet);
        });

        // when
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
            refundStepPersistencePort.update(wallet);
            return wallet;
        });

        // then
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET)).orElseThrow();
        assertThat(sut.getWalletReport()).isEqualTo(report);
    }

    // 지갑이 아닌 줄과 불일치가 아닌 줄은 값 칸이 모두 비어 있다
    @Test
    void 불일치가_아닌_줄은_지갑_값이_없다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        });

        // when
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();

        // then
        assertThat(sut.getWalletReport()).isNull();
    }

    // 경우 3: 환불 행을 잠그지 않고 단계 행을 만들면, 같은 환불을 다른 곳이 처리하는 중에도 끼어들 수 있어 막는다
    @Test
    void 환불을_잠그지_않고_단계_줄을_저장하면_거부한다() {
        // when & then
        assertThatThrownBy(() -> inTransaction(() ->
                refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("단계 행은 같은 트랜잭션에서 환불을 잠금 조회한 뒤에만 저장할 수 있습니다. refundId=" + refundId);
        assertThat(refundStepJpaRepository.count()).isZero();
    }

    @Test
    void 환불을_잠그지_않고_단계_줄을_고치면_거부한다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        });

        // when & then: 단계 행은 이 트랜잭션에서 불러왔지만 환불 행은 잠그지 않았다
        assertThatThrownBy(() -> inTransaction(() -> {
            RefundStep step = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER).orElseThrow();
            step.succeed();
            refundStepPersistencePort.update(step);
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("단계 행은 같은 트랜잭션에서 환불을 잠금 조회한 뒤에만 저장할 수 있습니다. refundId=" + refundId);
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();
        assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
    }

    // 잠근 환불이 "아무 환불"이 아니라 "그 단계의 환불"이어야 한다(결정 4). 여러 환불을 한 트랜잭션에서 다루는 곳(#576)에서
    // 다른 환불을 잠근 것으로 이 환불의 단계 행을 쓰면, 이 환불을 처리하는 다른 요청과 동시에 고칠 수 있다
    @Test
    void 다른_환불을_잠갔어도_이_환불을_잠그지_않으면_단계_줄을_저장할_수_없다() {
        // given
        Long otherRefundId = inTransaction(() -> refundPersistencePort.saveNew(
                Refund.request(70L, 702L, 1, 1L, 7L, 78L, "상품2", "옵션2", 5_000L, null))).getId();
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(otherRefundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(otherRefundId, RefundStepType.ORDER, NOW));
        });

        // when & then: 새로 만들기
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(otherRefundId, RefundStepType.WALLET, NOW));
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("단계 행은 같은 트랜잭션에서 환불을 잠금 조회한 뒤에만 저장할 수 있습니다. refundId=" + otherRefundId);

        // when & then: 고치기. 단계 행은 이 트랜잭션에서 불러왔지만 잠근 환불은 다른 환불이다
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep step = refundStepPersistencePort.findByRefundIdAndType(otherRefundId, RefundStepType.ORDER).orElseThrow();
            step.succeed();
            refundStepPersistencePort.update(step);
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("단계 행은 같은 트랜잭션에서 환불을 잠금 조회한 뒤에만 저장할 수 있습니다. refundId=" + otherRefundId);
        List<RefundStep> otherSteps = inTransaction(() -> refundStepPersistencePort.findAllByRefundId(otherRefundId));
        assertThat(otherSteps).extracting(RefundStep::getType, RefundStep::getStatus)
                .containsExactly(tuple(RefundStepType.ORDER, RefundStepStatus.IN_PROGRESS));
    }

    // 다른 트랜잭션에서 읽은 단계 객체는 그 사이 바뀌었을 수 있는 옛 값이다. 그 값으로 덮어쓰면 다른 요청이 남긴 결과가 사라지므로,
    // 환불은 잠갔어도 이 트랜잭션에서 불러온 단계 행만 고칠 수 있다
    @Test
    void 이_트랜잭션에서_불러오지_않은_단계_줄은_고칠_수_없다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        });
        RefundStep loadedElsewhere = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();
        loadedElsewhere.succeed();

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            refundStepPersistencePort.update(loadedElsewhere);
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이 트랜잭션에서 불러온 단계 행만 update할 수 있습니다. refundStepId=" + loadedElsewhere.getId());
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();
        assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.IN_PROGRESS);
    }

    // 경우 4: 단계 행은 그 단계 차례에 한 번만 만든다. 두 번 만들려는 것은 부르는 쪽 버그라 유일 제약 위반을 그대로 던진다
    @Test
    void 같은_환불의_같은_단계를_두_번_저장하면_실패한다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        });

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(refundStepJpaRepository.count()).isOne();
    }

    @Test
    void 고친_단계_줄이_커밋_뒤에_남는다() {
        // given
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundStepPersistencePort.saveNew(RefundStep.start(refundId, RefundStepType.ORDER, NOW));
        });

        // when: 서비스처럼 한 트랜잭션에서 환불을 잠그고, 단계 행을 읽어 바꾸고, update한다
        inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            RefundStep step = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER).orElseThrow();
            step.failForRetry("ORDER_API_FAILED", NOW.plusMinutes(5));
            refundStepPersistencePort.update(step);
            return null;
        });

        // then
        RefundStep sut = inTransaction(() ->
                refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER)).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(1);
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(6));
            softly.assertThat(sut.getLastReason()).isEqualTo("ORDER_API_FAILED");
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
