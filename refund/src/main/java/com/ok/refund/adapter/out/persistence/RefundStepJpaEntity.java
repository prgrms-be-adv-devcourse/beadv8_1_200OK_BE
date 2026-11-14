package com.ok.refund.adapter.out.persistence;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.refund.RefundSchema;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code refund_step} 테이블 매핑. 도메인 {@link com.ok.refund.domain.RefundStep}과 칸이 같지만 상태 규칙은 두지 않는다.
 * 값은 {@link RefundStepMapper}만 채운다. 값을 넣는 생성자와 메서드를 package-private로 둬 어댑터 밖에서 엔티티를 고치지 못하게 한다.
 * 환불은 ID 칸({@code refundId})으로만 가리키고 {@code @ManyToOne}을 두지 않는다. 도메인도 ID만 갖고, 연관을 두면
 * 단계 행을 읽을 때 환불 행까지 끌려와 "환불 행을 먼저 잠그고 첫 조회로 읽는다"는 규칙이 흐려지기 때문이다.
 * 외래키({@code fk_refund_step_refund_id})는 마이그레이션에 있어 DB가 정합성을 지킨다.
 */
@Entity
@Getter
@Table(
        name = "refund_step",
        catalog = RefundSchema.NAME,
        uniqueConstraints = @UniqueConstraint(name = "uk_refund_step_refund_id_step", columnNames = {"refund_id", "step"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundStepJpaEntity extends BaseTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_id", nullable = false)
    private Long refundId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 30)
    private RefundStepType step;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RefundStepStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    @Column(name = "last_reason", length = 100)
    private String lastReason;

    // 새 행 저장용. ID와 생성·수정 시각은 DB(IDENTITY)와 감사 리스너가 채우므로 받지 않는다.
    // 바뀌는 칸은 changeMutableState로 따로 채운다(매퍼가 새 행과 수정에 같은 규칙을 쓰게 하려고)
    RefundStepJpaEntity(Long refundId, RefundStepType step) {
        this.refundId = refundId;
        this.step = step;
    }

    // 바뀔 수 있는 칸만 받는다. 어느 환불의 어느 단계인지는 만들 때 정해지고 바뀌지 않아,
    // 여기서 받지 않는 것으로 수정 때 다른 환불·단계로 덮어쓰는 일을 막는다
    void changeMutableState(
            RefundStepStatus status,
            int attemptCount,
            LocalDateTime nextCheckAt,
            String lastReason
    ) {
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextCheckAt = nextCheckAt;
        this.lastReason = lastReason;
    }
}
