package com.ok.payout.application.port.in;

/**
 * 잠기거나 멈춘 주문을 관리자가 해결하는 입력 포트 (#568). 구현은 {@code application.service.PayoutFailureResolutionService}.
 */
public interface PayoutFailureResolutionUseCase {

    // 주문의 해결되지 않은 실패 기록을 모두 "다시 처리"로 해결하고, 저장한 해결 기록을 돌려줌
    // 잠기지도 멈추지도 않은 주문(이미 해결됨·잘못된 주문 ID 포함)이면 NOTHING_TO_RESOLVE(409)
    PayoutOrderResolutionResult resolveOrder(PayoutOrderResolveCommand command);
}
