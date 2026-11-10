package com.ok.payout.adapter.out.persistence;

import com.ok.payout.application.port.out.PayoutOutboxRow;
import com.ok.payout.application.service.PayoutOutboxService;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// outbox 표의 저장·조회와 DB 제약(UK, FK)을 실제 MySQL + Flyway 마이그레이션으로 확인
// 자동 롤백이 없으므로 이 테스트가 만든 행만 직접 지운다
class PayoutOutboxPersistenceIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 번호대 (스냅샷 job_execution_id UNIQUE 충돌 방지)
    private static final AtomicLong JOB_EXECUTION_ID_SEQ = new AtomicLong(4_500_000L);
    private static final LocalDateTime PERIOD_TO = LocalDateTime.of(2026, 11, 9, 0, 0);

    @Autowired
    private PayoutOutboxJpaRepository outboxRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutSnapshotJpaRepository snapshotRepository;

    @Autowired
    private PayoutOutboxService payoutOutboxService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> snapshotIds = new ArrayList<>();

    // 리포지토리에는 삭제 기능이 없으므로 정리용으로만 SQL 직접 실행 (FK 순서: outbox, payout, snapshot)
    @AfterEach
    void tearDown() {
        for (Long snapshotId : snapshotIds) {
            jdbcTemplate.update(
                    "DELETE FROM payout_outbox WHERE payout_id IN (SELECT id FROM payout WHERE snapshot_id = ?)",
                    snapshotId);
            jdbcTemplate.update("DELETE FROM payout WHERE snapshot_id = ?", snapshotId);
            jdbcTemplate.update("DELETE FROM payout_snapshot WHERE id = ?", snapshotId);
        }
        snapshotIds.clear();
    }

    @Test
    void 저장하면_PENDING_상태로_id와_시각이_채워진다() {
        // given
        Payout payout = savePayout(1L, 9_000L);

        // when
        Long id = outboxRepository.save(PayoutOutbox.pending(payout.getId())).getId();

        // then
        PayoutOutbox sut = outboxRepository.findById(id).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPayoutId()).isEqualTo(payout.getId());
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(sut.getAttemptCount()).isZero();
            softly.assertThat(sut.getLastError()).isNull();
            softly.assertThat(sut.getSentAt()).isNull();
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(sut.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void 보냄_표시와_실패_기록이_저장된다() {
        // given
        PayoutOutbox failed = outboxRepository.save(PayoutOutbox.pending(savePayout(1L, 9_000L).getId()));
        PayoutOutbox sent = outboxRepository.save(PayoutOutbox.pending(savePayout(2L, 5_000L).getId()));
        LocalDateTime sentAt = LocalDateTime.of(2026, 11, 9, 13, 0);

        // when
        failed.recordFailure("x".repeat(PayoutOutbox.MAX_ERROR_LENGTH + 10));
        sent.markSent(sentAt);
        outboxRepository.saveAll(List.of(failed, sent));

        // then
        PayoutOutbox savedFailed = outboxRepository.findById(failed.getId()).orElseThrow();
        PayoutOutbox savedSent = outboxRepository.findById(sent.getId()).orElseThrow();
        assertThat(savedFailed).isNotNull();
        assertThat(savedSent).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(savedFailed.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(savedFailed.getAttemptCount()).isEqualTo(1);
            softly.assertThat(savedFailed.getLastError()).hasSize(PayoutOutbox.MAX_ERROR_LENGTH);
            softly.assertThat(savedSent.getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
            softly.assertThat(savedSent.getSentAt()).isEqualTo(sentAt);
        });
    }

    @Test
    void 같은_정산으로_두_줄을_저장하면_UNIQUE_위반이다() {
        // given
        Payout payout = savePayout(1L, 9_000L);
        outboxRepository.save(PayoutOutbox.pending(payout.getId()));

        // when, then
        assertThatThrownBy(() -> outboxRepository.save(PayoutOutbox.pending(payout.getId())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_payout_outbox_payout_id");
    }

    @Test
    void 없는_정산_ID로_저장하면_외래키_위반이다() {
        // when, then
        assertThatThrownBy(() -> outboxRepository.save(PayoutOutbox.pending(Long.MAX_VALUE)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_payout_outbox_payout_id");
    }

    @Test
    void 시도_수가_같으면_대기_줄만_id_순서로_정산_값과_함께_조회한다() {
        // given
        Payout first = savePayout(1L, 9_000L);
        Payout second = savePayout(2L, 5_000L);
        Payout sentPayout = savePayout(3L, 7_000L);
        PayoutOutbox firstOutbox = outboxRepository.save(PayoutOutbox.pending(first.getId()));
        PayoutOutbox sentOutbox = PayoutOutbox.pending(sentPayout.getId());
        sentOutbox.markSent(LocalDateTime.of(2026, 11, 9, 13, 0));
        outboxRepository.save(sentOutbox);
        PayoutOutbox secondOutbox = outboxRepository.save(PayoutOutbox.pending(second.getId()));

        // when
        List<PayoutOutboxRow> sut = outboxRepository.findPendingRows();

        // then
        List<Long> ids = new ArrayList<>();
        for (PayoutOutboxRow row : sut) {
            ids.add(row.outboxId());
        }
        assertThat(ids).containsSubsequence(firstOutbox.getId(), secondOutbox.getId())
                .doesNotContain(sentOutbox.getId());
        PayoutOutboxRow firstRow = sut.get(ids.indexOf(firstOutbox.getId()));
        assertThat(firstRow).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(firstRow.payoutId()).isEqualTo(first.getId());
            softly.assertThat(firstRow.payeeId()).isEqualTo(1L);
            softly.assertThat(firstRow.amount()).isEqualTo(9_000L);
            softly.assertThat(firstRow.payoutCreatedAt()).isEqualTo(
                    payoutRepository.findById(first.getId()).orElseThrow().getCreatedAt());
        });
    }

    @Test
    void 실패한_줄도_다시_조회하고_덜_시도한_줄부터_시도_수가_같으면_id_순서로_준다() {
        // given: id 순서와 반대로 시도 수를 둬, id 순(인덱스 순)으로 읽으면 통과하지 못하게 함
        PayoutOutbox failedTwice = saveOutboxWithFailures(1L, 2);
        PayoutOutbox failedOnce = saveOutboxWithFailures(2L, 1);
        PayoutOutbox neverTried = saveOutboxWithFailures(3L, 0);
        PayoutOutbox neverTriedLater = saveOutboxWithFailures(4L, 0);
        List<Long> savedIds = List.of(failedTwice.getId(), failedOnce.getId(), neverTried.getId(), neverTriedLater.getId());

        // when
        List<PayoutOutboxRow> sut = outboxRepository.findPendingRows();

        // then: 다른 테스트 클래스가 남긴 줄은 빼고 이 테스트의 줄만 순서를 봄
        List<Long> ids = new ArrayList<>();
        for (PayoutOutboxRow row : sut) {
            if (savedIds.contains(row.outboxId())) {
                ids.add(row.outboxId());
            }
        }
        assertThat(ids).containsExactly(
                neverTried.getId(), neverTriedLater.getId(), failedOnce.getId(), failedTwice.getId());
    }

    @Test
    void 보냄_표시와_실패_기록은_부른_쪽_트랜잭션이_롤백돼도_남는다() {
        // given
        PayoutOutbox toSend = saveOutboxWithFailures(1L, 0);
        PayoutOutbox toFail = saveOutboxWithFailures(2L, 0);

        // when: 발행 Step 트랜잭션처럼 바깥 트랜잭션 안에서 부른 뒤 롤백
        transactionTemplate.executeWithoutResult(status -> {
            payoutOutboxService.markSent(toSend.getId());
            payoutOutboxService.recordFailure(toFail.getId(), "브로커 응답 없음");
            status.setRollbackOnly();
        });

        // then
        PayoutOutbox savedSent = outboxRepository.findById(toSend.getId()).orElseThrow();
        PayoutOutbox savedFailed = outboxRepository.findById(toFail.getId()).orElseThrow();
        assertThat(savedSent).isNotNull();
        assertThat(savedFailed).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(savedSent.getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
            softly.assertThat(savedFailed.getAttemptCount()).isEqualTo(1);
        });
    }

    private PayoutOutbox saveOutboxWithFailures(Long sellerId, int failures) {
        PayoutOutbox outbox = PayoutOutbox.pending(savePayout(sellerId, 1_000L).getId());
        for (int i = 0; i < failures; i++) {
            outbox.recordFailure("브로커 응답 없음");
        }
        return outboxRepository.save(outbox);
    }

    // 항목 없는 판매자 정산을 만들고 금액만 지정해 저장 (outbox 저장에는 항목이 필요 없음)
    private Payout savePayout(Long sellerId, long amount) {
        PayoutSnapshot snapshot = snapshotRepository.save(PayoutSnapshot.create(
                JOB_EXECUTION_ID_SEQ.incrementAndGet(), null, PERIOD_TO, PayoutRunType.SCHEDULED));
        snapshotIds.add(snapshot.getId());
        Payout payout = Payout.forSeller(snapshot.getId(), sellerId, List.of());
        ReflectionTestUtils.setField(payout, "amount", amount);
        return payoutRepository.saveAll(List.of(payout)).get(0);
    }
}
