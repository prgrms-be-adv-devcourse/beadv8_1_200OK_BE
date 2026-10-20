package com.ok.payout.service.dto;

// 정산 항목이 관리자 조정 건일 때의 조정 정보
public record PayoutAdjustmentResult(
        // 관리자 조정 번호 (1 이상). 판매자 응답에는 넣지 않음 (판매자가 쓸 데 없는 내부 번호)
        Long adjustmentId,
        // 조정한 관리자 회원 ID. 판매자 응답에는 넣지 않음 (내부 직원 정보)
        Long adminId,
        // 조정 사유. 관리자·판매자 모두 봄. 관리자가 입력한 원문 그대로라 화면에 출력할 때 HTML 이스케이프 필요
        String reason
) {
}
