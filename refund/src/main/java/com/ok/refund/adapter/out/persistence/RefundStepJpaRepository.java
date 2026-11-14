package com.ok.refund.adapter.out.persistence;

import com.ok.refund.domain.RefundStepType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * {@link RefundStepJpaEntity}의 Spring Data 리포지토리. 서비스는 출력 포트 {@code RefundStepPersistencePort}를 쓰고,
 * {@link RefundPersistenceAdapter}가 이 리포지토리로 포트를 구현한다.
 * 조회는 모두 잠그지 않는 읽기다. 단계 행은 따로 잠그지 않고, 같은 트랜잭션에서 환불 행을 먼저 잠그는 것으로 대신한다(결정 4).
 * 두 조회 모두 유일 제약 {@code uk_refund_step_refund_id_step}(refund_id, step)의 인덱스 앞쪽 칸(refund_id)으로 찾는다.
 */
public interface RefundStepJpaRepository extends JpaRepository<RefundStepJpaEntity, Long> {

    Optional<RefundStepJpaEntity> findByRefundIdAndStep(Long refundId, RefundStepType step);

    List<RefundStepJpaEntity> findAllByRefundIdOrderByIdAsc(Long refundId);
}
