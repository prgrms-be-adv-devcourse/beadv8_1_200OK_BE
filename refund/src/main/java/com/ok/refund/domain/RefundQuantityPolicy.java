package com.ok.refund.domain;

import java.util.List;

/**
 * 환불 요청 개수가 그 주문 상품의 남은 개수 안인지 판단한다.
 * 남은 개수는 주문이 알려 준 산 개수에서 이미 취소된(처리 중·완료) 개수를 뺀 것이다.
 * 주문은 환불 뒤에도 산 개수를 줄이지 않고 보내 주므로(주문과의 약속), 이미 취소된 개수는 환불 기록에서 센다(#414).
 * 넘는지 여부만 돌려주고 응답(409)은 부르는 쪽이 정한다. 같은 비교를 하는 배송비 정책은 넘으면 기록이 어긋난 버그로 보고 500을 내지만,
 * 요청 때 넘는 것은 구매자 입력 문제라 배송비 판단 전에 이 정책으로 먼저 거른다.
 */
public final class RefundQuantityPolicy {

    private RefundQuantityPolicy() {
    }

    /**
     * 요청 개수가 남은 개수 이하인지 판단한다.
     *
     * @param requested   이번에 요청한 개수
     * @param ordered     주문이 알려 준 산 개수 (환불 뒤에도 줄지 않은 값)
     * @param orderItemId 이번 요청의 주문 상품
     * @param cancelled   같은 묶음에서 이미 취소된(처리 중·완료) 개수를 주문 상품별로 모은 값. 이번 요청은 요청됨이라 들어 있지 않다
     * @return 요청 개수 + 이미 취소된 개수가 산 개수 이하이면 {@code true}
     */
    public static boolean isWithinRemaining(long requested, long ordered, Long orderItemId, List<CancelledQuantity> cancelled) {
        return requested + cancelledQuantityOf(cancelled, orderItemId) <= ordered;
    }

    // 집계는 주문 상품별 한 줄이다. 이 상품의 줄이 없으면 이미 취소된 것이 없다
    private static long cancelledQuantityOf(List<CancelledQuantity> cancelled, Long orderItemId) {
        for (CancelledQuantity each : cancelled) {
            if (each.orderItemId().equals(orderItemId)) {
                return each.quantity();
            }
        }
        return 0;
    }
}
