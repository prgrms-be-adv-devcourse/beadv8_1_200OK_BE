package com.ok.refund.service.dto;

import java.util.List;

/**
 * 주문 모듈이 알려 준 환불용 주문 상품 정보. 주문 모듈 타입을 가져오지 않고 값으로 옮겨 담는다.
 *
 * @param status          주문 상품 상태 이름 (예: {@code PREPARING}). 주문 모듈 enum을 import하지 않으려고 문자열로 받는다
 * @param unitPrice       주문 시점 단가 (원)
 * @param quantity        산 개수
 * @param shippingFee     판매자 묶음 배송비 (원)
 * @param remoteAreaFee   판매자 묶음 도서산간 추가 운임 (원)
 * @param sellerGroupItems 같은 판매자 묶음의 상품별 주문 수량 (이 상품 포함)
 */
public record RefundableOrderItem(
        Long orderId,
        Long orderItemId,
        Long buyerId,
        Long sellerId,
        Long productOptionId,
        String productName,
        String optionName,
        String status,
        long unitPrice,
        int quantity,
        long shippingFee,
        long remoteAreaFee,
        List<SellerGroupItem> sellerGroupItems
) {

    /** 출고 전 취소만 받는다. 이 값 외의 상태(결제 전·결제 실패·배송 중·전부 취소 등)는 모두 요청할 수 없다 */
    public static final String CANCELLABLE_STATUS = "PREPARING";

    public boolean isCancellable() {
        return CANCELLABLE_STATUS.equals(status);
    }
}
