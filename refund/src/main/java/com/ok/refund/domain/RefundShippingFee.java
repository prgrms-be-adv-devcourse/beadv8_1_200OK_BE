package com.ok.refund.domain;

/**
 * 환불 한 건의 배송비 판단 결과.
 * 판단이 "받을 수 없음"이면 금액 칸은 모두 0이고 {@code allowed}가 거짓이다.
 * 예외로 알리지 않고 값으로 돌려주는 이유: 요청 때는 409로 막고 승인 때는 자동 거절하는 등 부르는 쪽마다 처리가 달라서다.
 *
 * @param shippingFeeAmount         돌려줄 배송비 (원)
 * @param remoteAreaFeeAmount       돌려줄 도서산간 운임 (원)
 * @param deductedShippingFeeAmount 환불 금액에서 뺄 배송비 (원)
 * @param allowed                   이 취소를 받을 수 있는지. 취소 금액이 빼야 할 배송비보다 작으면 거짓
 */
public record RefundShippingFee(
        long shippingFeeAmount,
        long remoteAreaFeeAmount,
        long deductedShippingFeeAmount,
        boolean allowed
) {

    private static final RefundShippingFee NONE = new RefundShippingFee(0, 0, 0, true);
    private static final RefundShippingFee NOT_ALLOWED = new RefundShippingFee(0, 0, 0, false);

    public RefundShippingFee {
        if (shippingFeeAmount < 0 || remoteAreaFeeAmount < 0 || deductedShippingFeeAmount < 0) {
            throw new IllegalArgumentException("배송비 판단 금액은 0 이상이어야 합니다.");
        }
    }

    /** 배송비를 돌려주지도 빼지도 않는다 */
    public static RefundShippingFee none() {
        return NONE;
    }

    /** 취소 금액이 빼야 할 배송비보다 작아 이 취소를 받을 수 없다 */
    public static RefundShippingFee notAllowed() {
        return NOT_ALLOWED;
    }

    public static RefundShippingFee refund(long shippingFeeAmount, long remoteAreaFeeAmount) {
        return new RefundShippingFee(shippingFeeAmount, remoteAreaFeeAmount, 0, true);
    }

    public static RefundShippingFee deduct(long deductedShippingFeeAmount) {
        return new RefundShippingFee(0, 0, deductedShippingFeeAmount, true);
    }
}
