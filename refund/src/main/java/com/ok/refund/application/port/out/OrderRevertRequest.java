package com.ok.refund.application.port.out;

/**
 * 주문에 보내는 취소 기록 되돌리기 요청 값(#622). 메시지 모양(토픽·JSON 필드 이름)은 어댑터가 정하고, 서비스는 이 값만 넘긴다.
 *
 * @param refundId    주문이 되돌릴 취소 기록을 찾고, 같은 요청을 두 번 받아도 한 번만 되돌리도록 중복을 가르는 값(PR #537 약속).
 *                    취소 반영 때 보낸 refundId와 같다
 * @param orderItemId 취소 기록을 되돌릴 주문 상품
 * @param quantity    되돌릴 취소 수량. 취소 반영 때 보낸 수량과 같다
 */
public record OrderRevertRequest(
        Long refundId,
        Long orderId,
        Long orderItemId,
        int quantity
) {
}
