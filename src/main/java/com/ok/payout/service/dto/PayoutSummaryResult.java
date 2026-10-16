package com.ok.payout.service.dto;

import com.ok.payout.domain.Payout;

import java.time.LocalDateTime;

// 정산 목록의 정산 1건. 서비스가 엔티티(Payout) 대신 컨트롤러에 넘기는 값
public record PayoutSummaryResult(
        // 정산 ID (payout.id)
        Long payoutId,
        // 이 정산을 확정한 스냅샷 ID. 같은 배치 실행에서 확정된 정산끼리 같은 값
        Long snapshotId,
        // 수취인(판매자) 회원 ID. 시스템 몫이면 null
        Long payeeId,
        // 시스템(플랫폼) 몫 여부. 판매 수수료를 모은 정산이면 true
        boolean system,
        // 정산 금액 (원). 이 정산에 묶인 정산 항목 금액의 합. 조정 때문에 음수일 수 있음
        Long amount,
        // 정산 확정 시각 (payout.created_at). 목록의 기간 필터와 정렬 기준
        LocalDateTime createdAt
) {

    public static PayoutSummaryResult from(Payout payout) {
        return new PayoutSummaryResult(payout.getId(), payout.getSnapshotId(), payout.getPayeeId(),
                payout.isSystem(), payout.getAmount(), payout.getCreatedAt());
    }
}
