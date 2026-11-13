package com.ok.refund.application.port.out;

/**
 * 주문에 취소 반영을 요청한 결과. 주문이 받아 줬는지(반영됨), 막았는지(거절됨)만 알린다.
 * 응답을 아예 못 받았거나 그 밖의 오류 상태는 결과가 아니라 예외({@code ORDER_API_FAILED})로 알린다. 다시 시도하면 낫는 일시적 실패와,
 * 다시 보내도 같은 답이 오는 주문의 거절을 서비스가 다르게 다루려고 나눴다.
 *
 * @param rejected    주문이 반영을 막았으면 true
 * @param orderDetail 주문이 준 설명 문장. 로그에만 쓰고 판단이나 DB 저장에는 쓰지 않는다(문구가 바뀔 수 있고 주문 쪽 표현이라서).
 *                    null을 두지 않는다: 반영됨이면 빈 문자열, 거절인데 설명이 없으면 "주문 응답에 설명 없음"
 */
public record OrderCancellationResult(boolean rejected, String orderDetail) {

    public OrderCancellationResult {
        // 호출하는 쪽마다 null을 걱정하지 않게 만드는 곳에서 막는다
        if (orderDetail == null) {
            throw new IllegalArgumentException("주문 설명은 null일 수 없습니다.");
        }
    }

    public static OrderCancellationResult reflected() {
        return new OrderCancellationResult(false, "");
    }

    public static OrderCancellationResult rejected(String orderDetail) {
        return new OrderCancellationResult(true, orderDetail);
    }
}
