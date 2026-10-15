package com.ok.payout.service.dto;

import java.time.LocalDateTime;

// 정산 상세 1건. 서비스가 엔티티(Payout, PayoutSnapshot) 대신 컨트롤러에 넘기는 값
public record PayoutDetailResult(
        // 정산 ID (payout.id)
        Long payoutId,
        // 이 정산을 확정한 스냅샷 ID
        Long snapshotId,
        // 수취인(판매자) 회원 ID. 시스템 몫이면 null
        Long payeeId,
        // 시스템(플랫폼) 몫 여부. 판매 수수료를 모은 정산이면 true
        boolean system,
        // 정산 금액 (원). 이 정산에 묶인 정산 항목 금액의 합. 조정 때문에 0원 이하일 수 있음
        Long amount,
        // 정산 확정 시각 (payout.created_at)
        LocalDateTime createdAt,
        // 정산 대상 기간 시작 (스냅샷 period_from, 포함). 정산 이력이 없던 첫 실행이면 null
        LocalDateTime periodFrom,
        // 정산 대상 기간 끝 (스냅샷 period_to, 미포함)
        LocalDateTime periodTo,
        // 이 정산에 묶인 정산 항목 수. 항목 목록은 별도 API(GET /payouts/{payoutId}/items)로 페이징 조회
        long itemCount,
        // 같은 스냅샷의 시스템 몫 정산 ID (이 판매자의 수수료 항목이 들어 있음)
        // 관리자가 판매자 정산을 볼 때만 값. 판매자는 이 정산을 열 수 없어(403) null, 시스템 몫 정산을 볼 때도 null
        Long systemPayoutId,
        // 이 판매자 판매에서 떼어 간 수수료 합계 (원). 판매자 정산일 때 값(관리자·판매자 모두), 시스템 몫 정산이면 null
        Long sellerFeeAmount,
        // 위 수수료 항목 수. sellerFeeAmount와 같은 조건으로 값이 있음
        Long sellerFeeItemCount
) {
}
