package com.ok.payout.service.dto;

// draftStep 1회차 결과 (#90). tasklet이 다음 회차를 부를지 정하고, 배치 실행 기록에 성공·실패 판매자 수를 나눠 남기는 데 씀
// 실패 수를 따로 두는 이유: 판매자 단위 격리 뒤로는 판매자가 실패해도 Job이 COMPLETED라, 실행 기록에서 실패를 볼 수 있어야 함
public record PayoutDraftResult(
        // 이번 회차에 뽑은 판매자 수 (실패 포함). 0이면 남은 판매자가 없음 → tasklet 종료
        int sellerCount,
        // 그중 저장에 실패해 UNEXPECTED_ERROR로 기록한 판매자 수
        int failedSellerCount
) {
    // 성공한 판매자 수 (배치 write count)
    public int draftedSellerCount() {
        return sellerCount - failedSellerCount;
    }
}
