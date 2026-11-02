package com.ok.refund.domain;

/**
 * 환불 한 건에서 정한 배송비 금액들.
 *
 * @param shippingFee         돌려줄 배송비 (원). 묶음 전부 취소일 때만 = 묶음 배송비 + 같은 묶음 앞 요청에서 뺀 배송비
 * @param remoteAreaFee       돌려줄 도서산간 추가 운임 (원). 묶음 전부 취소일 때만
 * @param deductedShippingFee 일부 취소로 무료배송 기준이 깨져 환불에서 뺄 기본 배송비 (원). 아니면 0
 */
public record RefundShippingFee(long shippingFee, long remoteAreaFee, long deductedShippingFee) {
}
