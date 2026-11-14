package com.ok.payout.application.port.in;

import org.springframework.data.domain.Page;

/**
 * 막힌 주문 집계 표(#582)를 원본에서 다시 판정해 맞추고(#582), 관리자 목록으로 보여 주는(#569) 입력 포트. 구현은 {@code application.service.PayoutBlockedOrderService}.
 */
public interface PayoutBlockedOrderUseCase {

    // 정산 Job이 끝날 때 부름: 표에 있는 주문과 반영 표시 뒤에 생긴 실패 기록의 주문만 원본으로 다시 판정해 넣기·고치기·지우기
    // 다 끝나야 반영 표시를 올림 (중간에 실패하면 다음 갱신이 같은 범위를 처음부터 다시 함)
    void refresh();

    // 관리자 막힌 주문 목록 (#569): 집계 표를 읽기만 함. 마지막 실패 시각 최신순(같으면 주문 ID 역순), 막힌 주문이 없으면 빈 페이지
    // 목록 조건은 해결 API(#568)의 409 판정과 같음 (표가 같은 조건으로 채워짐)
    Page<PayoutBlockedOrderResult> getBlockedOrders(PayoutBlockedOrderSearchQuery query);
}
