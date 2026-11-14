package com.ok.refund.adapter.out.persistence;

import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

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
            softly.assertThat(sut.getNextCheckAt()).isEqualTo(NOW.plusMinutes(1));
            softly.assertThat(sut.getLastReason()).isNull();
        });
        List<RefundStep> all = inTransaction(() -> refundStepPersistencePort.findAllByRefundId(refundId));
        assertThat(all).extracting(RefundStep::getType).containsExactly(RefundStepType.ORDER, RefundStepType.WALLET);
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
