package com.ok.refund.adapter.out.persistence;

import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    // 환불 하나에 줄이 여럿이어도 한 번만 돌려주려고 환불로 묶고, 가장 오래 기다린 줄의 시각 순으로 정렬한다.
    // 조건 (status IN, next_check_at <=)은 인덱스 idx_refund_step_status_next_check_at으로 찾는다(#576).
    // 처리 중인 환불만 고른다. 끝난 환불은 다시 진행할 일이 없고 줄에 쓰지도 않으므로(#616 결정 15), 고르면 시각이 바뀌지 않아
    // 매 회차 맨 앞에 다시 뽑혀 다른 환불을 밀어낸다(PR #628 검수)
    @Query("""
            SELECT s.refundId FROM RefundStepJpaEntity s, RefundJpaEntity r
            WHERE r.id = s.refundId AND r.status = :refundStatus
              AND s.status IN :statuses AND s.nextCheckAt <= :now
            GROUP BY s.refundId
            ORDER BY MIN(s.nextCheckAt), s.refundId
            """)
    List<Long> findRefundIdsDue(@Param("refundStatus") RefundStatus refundStatus,
                                @Param("statuses") Collection<RefundStepStatus> statuses, @Param("now") LocalDateTime now,
                                Pageable pageable);
}
