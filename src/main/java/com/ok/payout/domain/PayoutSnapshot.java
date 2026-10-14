package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

import static jakarta.persistence.GenerationType.IDENTITY;

// 정산 배치 1회가 맡은 정산 대상의 기준 (Job 실행 1회 = 스냅샷 1건)
// 대상 목록은 payout_snapshot_item에 기록하고, draft·검증·확정은 모두 이 스냅샷 기준으로 처리
@Entity
@Immutable
@Table(name = "payout_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutSnapshot extends BaseTime {

    @Id
    @GeneratedValue(strategy = IDENTITY)
    private Long id;

    // Spring Batch 실행 ID (BATCH_JOB_EXECUTION). 배치 실행 기록을 정리(삭제)할 수 있도록 외래키를 두지 않음
    @Column(nullable = false, unique = true)
    private Long jobExecutionId;

    // 조회 하한 (포함). null이면 하한 없음 (정산 이력이 없는 첫 실행)
    private LocalDateTime periodFrom;

    // 조회 상한 (미포함)
    @Column(nullable = false)
    private LocalDateTime periodTo;

    public static PayoutSnapshot create(Long jobExecutionId, LocalDateTime periodFrom, LocalDateTime periodTo) {
        PayoutSnapshot snapshot = new PayoutSnapshot();
        snapshot.jobExecutionId = jobExecutionId;
        snapshot.periodFrom = periodFrom;
        snapshot.periodTo = periodTo;
        return snapshot;
    }
}
