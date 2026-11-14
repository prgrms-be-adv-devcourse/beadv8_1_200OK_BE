package com.ok.refund.application.port.out;

import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepType;

import java.util.List;
import java.util.Optional;

/**
 * 환불 승인 뒤 단계 진행 기록({@link RefundStep})을 저장·조회하는 출력 포트(#574). 구현은 {@code adapter/out/persistence}의 JPA 어댑터다.
 * 저장은 {@link RefundPersistencePort}처럼 명시적으로 한다. 새 행은 {@link #saveNew}, 상태 변경은 {@link #update}를 부른다.
 * 단계 행은 따로 잠그지 않는다. 만들거나 고치기 전에 같은 트랜잭션에서 그 환불 행을
 * {@link RefundPersistencePort#findByIdForUpdate}로 잠가야 한다(결정 4). 잠금 순서를 "환불 행 → 단계 행" 하나로 두어 교착을 막는다.
 */
public interface RefundStepPersistencePort {

    /**
     * 새 단계 행을 바로 저장하고(INSERT를 즉시 보냄) ID와 생성 시각이 채워진 새 도메인을 돌려준다. 넘긴 객체는 바뀌지 않는다.
     *
     * @throws IllegalStateException 같은 트랜잭션에서 그 환불을 {@link RefundPersistencePort#findByIdForUpdate}로 잠그지 않았으면 (부르는 쪽 버그)
     * @throws org.springframework.dao.DataIntegrityViolationException 같은 환불의 같은 단계 행이 이미 있으면 (단계 행은 한 번만 만들므로 부르는 쪽 버그)
     */
    RefundStep saveNew(RefundStep step);

    /**
     * 도메인의 바뀌는 칸(상태, 시도 횟수, 다시 볼 시각, 마지막 이유)을 저장한다. 실제 UPDATE는 트랜잭션 커밋 때 나간다.
     * 같은 트랜잭션에서 불러오거나 저장한 단계 행에만 부른다.
     *
     * @throws IllegalStateException 같은 트랜잭션에서 그 환불을 잠그지 않았거나, 이 트랜잭션에서 불러오지 않은 단계 행이면 (부르는 쪽 버그)
     */
    void update(RefundStep step);

    Optional<RefundStep> findByRefundIdAndType(Long refundId, RefundStepType type);

    /** 한 환불의 단계 행을 만든 순서(ID 오름차순)로 돌려준다. */
    List<RefundStep> findAllByRefundId(Long refundId);
}
