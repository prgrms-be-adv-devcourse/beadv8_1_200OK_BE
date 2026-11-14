package com.ok.payout.application.port.in;

/**
 * 막힌 주문 집계 표(#582)를 원본에서 다시 판정해 맞추는 입력 포트. 구현은 {@code application.service.PayoutBlockedOrderService}.
 */
public interface PayoutBlockedOrderUseCase {

    // 정산 Job이 끝날 때 부름: 표에 있는 주문과 반영 표시 뒤에 생긴 실패 기록의 주문만 원본으로 다시 판정해 넣기·고치기·지우기
    // 다 끝나야 반영 표시를 올림 (중간에 실패하면 다음 갱신이 같은 범위를 처음부터 다시 함)
    void refresh();
}
