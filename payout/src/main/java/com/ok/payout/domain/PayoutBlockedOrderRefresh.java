package com.ok.payout.domain;

import com.ok.common.jpa.entity.BaseTime;
import com.ok.payout.PayoutSchema;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 막힌 주문 집계 표의 갱신 상태 (#582): 어디까지의 실패 기록을 표에 반영했는지. 한 줄만 둠(id = 1, 마이그레이션이 넣음)
// 다음 갱신은 이 번호 뒤의 실패 기록만 읽음 → 운영 시작 이후 실패 기록 전체를 매번 읽지 않음 (payout/CLAUDE.md 설계 전제)
// 막힌 주문 표 줄 안에 두지 않는 이유: 막힌 주문이 0개면 줄이 없어 표시도 사라짐
// 스냅샷 표에 두지 않는 이유: 스냅샷은 고치지 않는 표(@Immutable)라 예외를 그 표까지 넓히게 됨
@Entity
@Table(name = "payout_blocked_order_refresh", catalog = PayoutSchema.NAME)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutBlockedOrderRefresh extends BaseTime {

    // 한 줄뿐인 갱신 상태의 ID
    public static final long SINGLE_ROW_ID = 1L;

    @Id
    private Long id;

    // 표에 반영한 마지막 실패 기록 ID (payout_failure.id)
    @Column(nullable = false)
    private Long lastFailureId;

    // 이 번호까지 반영함. 실패 기록 ID는 늘기만 하므로 뒤로 가지 않음
    public void advanceTo(long failureId) {
        if (failureId < lastFailureId) {
            throw new IllegalArgumentException("반영 표시는 뒤로 갈 수 없습니다. 지금=" + lastFailureId + ", 요청=" + failureId);
        }
        this.lastFailureId = failureId;
    }
}
