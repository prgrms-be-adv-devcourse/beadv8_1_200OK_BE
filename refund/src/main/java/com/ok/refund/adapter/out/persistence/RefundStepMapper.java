package com.ok.refund.adapter.out.persistence;

import com.ok.refund.domain.RefundStep;

/**
 * 도메인 {@link RefundStep}과 {@link RefundStepJpaEntity}를 서로 바꾼다. 영속 어댑터 안에서만 쓴다.
 * 칸이 늘면 도메인·엔티티·이 클래스 세 곳을 함께 고쳐야 한다({@link RefundMapper}와 같은 방식).
 */
final class RefundStepMapper {

    private RefundStepMapper() {
    }

    static RefundStep toDomain(RefundStepJpaEntity entity) {
        return RefundStep.restore(
                entity.getId(),
                entity.getRefundId(),
                entity.getStep(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextCheckAt(),
                entity.getLastReason(),
                entity.getCreatedAt());
    }

    // 새 행. 어느 환불의 어느 단계인지를 넣고, 바뀌는 칸은 수정과 같은 applyMutableState로 옮긴다.
    // 같은 메서드를 써야 새 행과 수정이 옮기는 칸 목록이 어긋나지 않는다.
    // 도메인의 createdAt은 옮기지 않는다. 생성 시각은 환불 행처럼 감사 리스너가 채운 값을 기준으로 한다
    static RefundStepJpaEntity toNewEntity(RefundStep step) {
        RefundStepJpaEntity entity = new RefundStepJpaEntity(step.getRefundId(), step.getType());
        applyMutableState(step, entity);
        return entity;
    }

    // 바뀔 수 있는 칸만 옮긴다: 상태, 시도 횟수, 다시 볼 시각, 마지막 이유
    static void applyMutableState(RefundStep step, RefundStepJpaEntity entity) {
        entity.changeMutableState(
                step.getStatus(),
                step.getAttemptCount(),
                step.getNextCheckAt(),
                step.getLastReason());
    }
}
