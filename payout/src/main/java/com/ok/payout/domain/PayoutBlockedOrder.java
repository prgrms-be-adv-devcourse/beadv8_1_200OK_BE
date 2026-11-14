package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

// 막힌 주문 집계 표 1줄 = 지금 정산이 막힌 주문 1개 (#582)
// 원본(실패 기록·해결 기록)에서 계산한 조회용 사본이라, 정산 표 중 예외로 수정·삭제를 허용함 (@Immutable 아님)
//   틀리면 원본에서 다시 계산해 바로잡음. 스냅샷은 이 표가 아니라 원본으로 판정함
// ID는 주문 ID: 한 주문은 한 줄 (잠김이면서 멈춤이어도 LOCKED 한 줄)
// Persistable: ID(주문 ID)를 직접 넣는 엔티티라 Spring Data가 새 줄도 "있는 줄"로 보고 저장마다 SELECT를 먼저 함(merge)
//   막힌 주문이 많을 때 줄마다 SELECT가 하나씩 더 나가지 않게, 새로 만든 줄인지 직접 알려 줌
@Entity
@Table(name = "payout_blocked_order", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutBlockedOrder extends BaseTime implements Persistable<Long> {

    @Id
    private Long orderId;

    // of()로 막 만든 줄만 true. DB에서 읽었거나 저장한 뒤에는 false (@PostLoad·@PostPersist)
    @Transient
    private boolean newRow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PayoutBlockedStatus status;

    // 성공한 실행에서 해결 안 된 실패가 남은 실행 수 (#549 멈춤 판정 값). 잠김은 이 값과 상관없음 (실패한 실행에서 잠기면 0일 수 있음)
    @Column(nullable = false)
    private int failedRunCount;

    // 해결 안 된 실패 기록 중 가장 늦은 기록 시각 (실행 성공 여부와 상관없음)
    @Column(nullable = false)
    private LocalDateTime lastFailedAt;

    // 초과 청구 "막힌 순간" 근거 (#570). 금액 검사 때 계산한 값이라 원본에서 다시 만들 수 없음
    //   그래서 Job 끝 다시 판정(change)은 이 값을 건드리지 않음. 초과 청구가 원인이 아니면 null
    @Embedded
    private PayoutOverchargeEvidence overchargeEvidence;

    // 금액 검사가 초과 청구 근거를 남기려고 넣는 예비 줄 (#570)
    // 실패 횟수·마지막 실패 시각은 Job 끝 판정이 다시 채움. 예비 줄은 목록에 나오지 않아 여기 값은 화면에 쓰이지 않음
    //   NOT NULL 칸이라 지금 실패 기록 시각과 0을 넣어 둠
    public static PayoutBlockedOrder pending(Long orderId, LocalDateTime failedAt, PayoutOverchargeEvidence evidence) {
        if (orderId == null || failedAt == null || evidence == null) {
            throw new IllegalArgumentException("예비 줄에는 주문 ID, 실패 시각, 초과 청구 근거가 필요합니다.");
        }
        PayoutBlockedOrder blockedOrder = new PayoutBlockedOrder();
        blockedOrder.orderId = orderId;
        blockedOrder.newRow = true;
        blockedOrder.status = PayoutBlockedStatus.PENDING;
        blockedOrder.failedRunCount = 0;
        blockedOrder.lastFailedAt = failedAt;
        blockedOrder.overchargeEvidence = evidence;
        return blockedOrder;
    }

    public static PayoutBlockedOrder of(Long orderId, PayoutBlockedStatus status, int failedRunCount,
                                        LocalDateTime lastFailedAt) {
        if (orderId == null) {
            throw new IllegalArgumentException("막힌 주문에는 주문 ID가 필요합니다.");
        }
        PayoutBlockedOrder blockedOrder = new PayoutBlockedOrder();
        blockedOrder.orderId = orderId;
        blockedOrder.newRow = true;
        blockedOrder.change(status, failedRunCount, lastFailedAt);
        return blockedOrder;
    }

    @Override
    public Long getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newRow = false;
    }

    // 정산 Job 끝 다시 판정 결과로 바꿈 (RESOLVED였어도 다시 막혔으면 LOCKED·STOPPED로)
    public void change(PayoutBlockedStatus status, int failedRunCount, LocalDateTime lastFailedAt) {
        if (status == null || status == PayoutBlockedStatus.RESOLVED || status == PayoutBlockedStatus.PENDING
                || lastFailedAt == null) {
            throw new IllegalArgumentException("다시 판정한 막힌 주문은 LOCKED·STOPPED 상태와 마지막 실패 시각이 필요합니다.");
        }
        this.status = status;
        this.failedRunCount = failedRunCount;
        this.lastFailedAt = lastFailedAt;
    }

    // 금액 검사가 이미 있는 줄(해결된 RESOLVED 줄)에 새 초과 청구 근거를 남김 (#570)
    // 상태는 그대로 둠: "해결됨"이 다음 Job 끝까지 목록에 보이는 동작(#582)을 바꾸지 않으려고. 줄 정리는 Job 끝 판정이 함
    public void recordEvidence(PayoutOverchargeEvidence evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("초과 청구 근거가 필요합니다.");
        }
        this.overchargeEvidence = evidence;
    }

    // Job 끝 판정에서 원인에 초과 청구가 없으면 근거를 비움 (#570): 다른 이유로 막힌 줄에 예전 초과 청구 근거가 남지 않게
    public void clearEvidence() {
        this.overchargeEvidence = null;
    }

    // 관리자가 해결함(#568): 줄을 지우는 일은 다음 정산 Job 끝 다시 판정이 함
    public void markResolved() {
        this.status = PayoutBlockedStatus.RESOLVED;
    }
}
