package com.ok.refund.application.port.out;

/**
 * 주문에 알리는 환불 취소 반영 값. 요청 모양(경로·필드 이름)은 어댑터가 정하고, 서비스는 이 값만 넘긴다.
 *
 * @param refundId            주문이 중복을 가르는 값. 같은 refundId로 다시 오면 더하지 않는다
 * @param orderItemId         취소할 주문 상품
 * @param quantity            취소 수량
 * @param deductedShippingFee 환불 금액에서 뺀 배송비 (원). 빼지 않았으면 0. 주문이 판매자 몫 배송비로 정산에 보낸다
 */
public record OrderCancellation(
        Long refundId,
        Long orderItemId,
        int quantity,
        long deductedShippingFee
) {
}
