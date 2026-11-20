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
 * 단계 행 조회는 유일 제약 {@code uk_refund_step_refund_id_step}(refund_id, step)의 인덱스 앞쪽 칸(refund_id)으로 찾는다.
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

    // 사람이 봐야 하는 처리 중 환불을 고른다(#577 멈춘 환불 찾기). 조건 넷 중 하나라도 맞으면 고른다.
    // 1. 멈춘 단계 줄이 있음(횟수를 다 씀, 다시 해도 같은 실패, 결과 불일치, BLOCKED_BY_STOPPED_STEP)
    // 2. 단계 줄이 하나도 없음(#574 전 승인된 옛 데이터)
    // 3. 앞 단계는 성공했는데 다음 줄이 없음. 다음 줄은 앞 단계 성공과 같은 구간에서 만들므로 정상 흐름에서는 생기지 않는다(RefundSagaPlan.fromSteps).
    //    주문 성공 뒤에는 지갑이나 재고 줄 중 하나가 있어야 한다(돈이 있으면 지갑, 0원이면 재고). 돌려줄 금액은 칸이 아니라 계산 값이라
    //    금액을 보지 않고 "둘 다 없음"으로 본다. 지갑 성공 뒤에는 재고 줄이 있어야 한다
    // 4. 지갑 줄이 결과 대기(다시 보내는 중이면 잠깐 시도 중)인 채로 오래됨. 결과 대기는 시간이 지나면 다시 보내며 기다리는 시각을
    //    새로 잡으므로, 처음부터 얼마나 기다렸는지는 결과를 처음 기다리기 시작한 시각(awaiting_since)으로 본다.
    //    발행 뒤 "결과 대기" 기록이 실패해 시도 중으로만 남은 줄은 그 값이 없어 줄을 만든 시각으로 대신 본다
    // 할 차례 줄은 넣지 않는다. 알람이 다시 시도하는 중이고, 횟수를 다 쓰면 멈춤이 되어 1번에 걸린다(#577 결정).
    // 한 시간에 한 번 도는 조회라 훑는 것을 받아들인다. refund.status로 시작하는 인덱스가 없어 끝난 환불까지 환불 표 전체를 훑는다.
    // 환불 이력이 커져 이 조회가 느려지면 refund(status) 인덱스를 더한다. 하위 조회는 유일 제약 (refund_id, step)의 인덱스로 찾는다
    @Query("""
            SELECT r.id FROM RefundJpaEntity r
            WHERE r.status = :processing AND (
                EXISTS (SELECT 1 FROM RefundStepJpaEntity s WHERE s.refundId = r.id AND s.status = :stopped)
                OR NOT EXISTS (SELECT 1 FROM RefundStepJpaEntity s WHERE s.refundId = r.id)
                OR (EXISTS (SELECT 1 FROM RefundStepJpaEntity s
                            WHERE s.refundId = r.id AND s.step = :order AND s.status = :succeeded)
                    AND NOT EXISTS (SELECT 1 FROM RefundStepJpaEntity s
                                    WHERE s.refundId = r.id AND s.step IN (:wallet, :product)))
                OR (EXISTS (SELECT 1 FROM RefundStepJpaEntity s
                            WHERE s.refundId = r.id AND s.step = :wallet AND s.status = :succeeded)
                    AND NOT EXISTS (SELECT 1 FROM RefundStepJpaEntity s WHERE s.refundId = r.id AND s.step = :product))
                OR EXISTS (SELECT 1 FROM RefundStepJpaEntity s
                           WHERE s.refundId = r.id AND s.step = :wallet AND s.status IN :waiting
                             AND COALESCE(s.awaitingSince, s.createdAt) <= :awaitingSince)
            )
            ORDER BY r.id
            """)
    List<Long> findRefundIdsNeedingAttention(@Param("processing") RefundStatus processing,
                                             @Param("stopped") RefundStepStatus stopped,
                                             @Param("succeeded") RefundStepStatus succeeded,
                                             @Param("waiting") Collection<RefundStepStatus> waiting,
                                             @Param("order") RefundStepType order,
                                             @Param("wallet") RefundStepType wallet,
                                             @Param("product") RefundStepType product,
                                             @Param("awaitingSince") LocalDateTime awaitingSince);
}
