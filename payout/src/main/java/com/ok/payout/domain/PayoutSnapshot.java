package com.ok.payout.domain;

import com.ok.payout.PayoutSchema;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.common.jpa.entity.BaseTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "payout_snapshot", catalog = PayoutSchema.NAME)
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

    // 이 정산이 어느 경로로 시작됐는지 (정기 스케줄러 / 관리자 실행 / 대기 건너뛰기). 나중에 실행 경로별로 구분해 보려고 남김
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutRunType runType;

    public static PayoutSnapshot create(Long jobExecutionId, LocalDateTime periodFrom, LocalDateTime periodTo,
                                        PayoutRunType runType) {
        // 실행 유형은 배치가 Job 파라미터에서 꺼내 넘기는 시스템 값이라 null이면 코드 버그 → 표준 예외로 막음
        if (runType == null) {
            throw new IllegalArgumentException("정산 실행 유형은 필수입니다. jobExecutionId=" + jobExecutionId);
        }
        PayoutSnapshot snapshot = new PayoutSnapshot();
        snapshot.jobExecutionId = jobExecutionId;
        snapshot.periodFrom = periodFrom;
        snapshot.periodTo = periodTo;
        snapshot.runType = runType;
        return snapshot;
    }
}
