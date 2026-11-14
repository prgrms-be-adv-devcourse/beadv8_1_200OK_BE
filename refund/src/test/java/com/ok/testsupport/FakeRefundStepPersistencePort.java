package com.ok.testsupport;

import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 단계 진행 표 저장(실제 영속 어댑터)을 감싸, 평소에는 그대로 DB에 저장하고 테스트가 고른 저장 한 번만 실패하게 하는 Fake.
 * "다른 모듈 호출은 성공했는데 그 뒤 기록 구간이 실패한" 경우를 실제 DB·트랜잭션으로 만들려고 둔다. 이런 실패는 DB를 끄지 않고는 만들 수 없다.
 * 실제 어댑터는 환불 행 포트도 함께 구현해, 이 Fake는 단계 줄 포트만 대신하고 환불 행 포트는 실제 어댑터가 그대로 받는다.
 * {@code AbstractIntegrationTest}의 {@code @ComponentScan}이 주워 가고 {@code @Primary}로 단계 줄 포트 주입을 대신한다.
 * 테스트마다 {@link #clear}로 비운다.
 */
@Component
@Primary
public class FakeRefundStepPersistencePort implements RefundStepPersistencePort {

    private final RefundStepPersistencePort persistenceAdapter;
    private final AtomicReference<FailingSave> failingSave = new AtomicReference<>();

    public FakeRefundStepPersistencePort(@Qualifier("refundPersistenceAdapter") RefundStepPersistencePort persistenceAdapter) {
        this.persistenceAdapter = persistenceAdapter;
    }

    /** 이 단계의 새 줄 저장이 다음에 한 번 실패하게 한다. 실패하면 그 트랜잭션 구간 전체가 되돌려진다 */
    public void failNextSaveNew(RefundStepType type) {
        failingSave.set(new FailingSave(SaveKind.NEW, type, null));
    }

    /** 이 단계 줄을 이 상태로 고쳐 저장하는 일이 다음에 한 번 실패하게 한다 */
    public void failNextUpdateTo(RefundStepType type, RefundStepStatus status) {
        failingSave.set(new FailingSave(SaveKind.UPDATE, type, status));
    }

    public void clear() {
        failingSave.set(null);
    }

    @Override
    public RefundStep saveNew(RefundStep step) {
        failIfChosen(SaveKind.NEW, step);
        return persistenceAdapter.saveNew(step);
    }

    @Override
    public void update(RefundStep step) {
        failIfChosen(SaveKind.UPDATE, step);
        persistenceAdapter.update(step);
    }

    @Override
    public Optional<RefundStep> findByRefundIdAndType(Long refundId, RefundStepType type) {
        return persistenceAdapter.findByRefundIdAndType(refundId, type);
    }

    @Override
    public List<RefundStep> findAllByRefundId(Long refundId) {
        return persistenceAdapter.findAllByRefundId(refundId);
    }

    // 고른 저장과 맞으면 한 번만 실패시키고 지운다. DB 연결 끊김 같은 일시적 오류를 흉내 내 표준 예외를 던진다
    // 새 줄 저장은 단계만 맞으면, 고쳐 저장은 단계와 바꾼 뒤 상태까지 맞아야 고른 저장이다
    private void failIfChosen(SaveKind kind, RefundStep step) {
        FailingSave chosen = failingSave.get();
        if (chosen == null || chosen.kind() != kind || chosen.type() != step.getType()) {
            return;
        }
        if (kind == SaveKind.UPDATE && chosen.status() != step.getStatus()) {
            return;
        }
        if (failingSave.compareAndSet(chosen, null)) {
            throw new IllegalStateException("단계 줄 저장에 실패했습니다(테스트). step=" + step.getType() + ", status=" + step.getStatus());
        }
    }

    private enum SaveKind { NEW, UPDATE }

    // status는 UPDATE일 때만 쓴다(고쳐 저장한 뒤의 상태)
    private record FailingSave(SaveKind kind, RefundStepType type, RefundStepStatus status) {
    }
}
