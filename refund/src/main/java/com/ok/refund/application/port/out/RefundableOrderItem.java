package com.ok.refund.application.port.out;

import com.ok.refund.domain.BundleShipping;

import java.util.Set;

/**
 * 주문 모듈이 알려 준 주문 상품 정보. 주문 모듈 타입을 가져오지 않고 값으로 옮겨 담는다.
 *
 * @param status    주문 상품 상태 이름 (예: {@code PREPARING}). 주문 모듈 enum을 import하지 않으려고 문자열로 받는다
 * @param unitPrice 주문 시점 단가 (원)
 * @param quantity  산 개수
 * @param bundle    이 상품이 속한 판매자 묶음의 배송비 값. 요청·승인 때 배송비를 판단할 때 쓴다(#410)
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
        BundleShipping bundle
) {

    /** 1단계는 출고 전 취소만 받는다. 이 값 외의 상태(결제 전, 배송 중, 전부 취소 등)는 모두 요청할 수 없다 */
    public static final String CANCELLABLE_STATUS = "PREPARING";

    // 승인 때 "이미 출고됐거나 취소된" 것으로 알고 있는 상태. PREPARING을 뺀 주문 모듈의 나머지 상태 전부다
    private static final Set<String> NO_LONGER_CANCELLABLE_STATUSES = Set.of(
            "SHIPPING", "DELIVERED", "PURCHASE_CONFIRMED", "CANCEL_REQUESTED", "REFUNDED", "PAYMENT_PENDING", "PAYMENT_FAILED");

    public boolean isCancellable() {
        return CANCELLABLE_STATUS.equals(status);
    }

    /**
     * 주문 모듈이 알려 준 상태가 "출고 전이 아님"으로 알려진 값인지 본다. 승인 때 이 값이면 요청을 자동 거절한다.
     * {@link #isCancellable()}의 반대가 아니다. {@code null}이나 모르는 값은 여기서 false다.
     * 그런 값은 주문 모듈의 버그나 새 상태 추가일 가능성이 높은데, 이를 자동 거절하면 구매자의 요청이
     * 주문 모듈의 잘못으로 조용히 끝나 버린다. 그래서 거절하지 않고 서버 오류(500)로 드러내 요청됨 그대로 남긴다.
     */
    public boolean isNoLongerCancellable() {
        return status != null && NO_LONGER_CANCELLABLE_STATUSES.contains(status);
    }
}
