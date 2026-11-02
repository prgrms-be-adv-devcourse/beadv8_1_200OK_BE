package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 환불 한 건에서 돌려줄 배송비와 뺄 배송비를 정한다.
 *
 * <ul>
 *   <li>묶음 전부 취소: 상자가 나가지 않으므로 묶음 배송비·추가 운임과, 같은 묶음 앞 요청에서 뺀 배송비까지 돌려준다.</li>
 *   <li>일부 취소로 남은 묶음 금액이 무료배송 기준 아래로 처음 내려감: 남은 상품을 보내려면 배송비를 내야 하므로 기본 배송비를 뺀다.
 *       이미 기준 아래였으면 앞 요청에서 뺐거나 처음부터 배송비를 냈으므로 또 빼지 않는다.</li>
 *   <li>그 밖: 배송비를 돌려주지도 빼지도 않는다.</li>
 * </ul>
 * 기준은 배송 모듈의 배송비 규칙(판매자 상품 합계가 기준 이상이면 0원, 아니면 기본 배송비, 기준이 없으면 항상 기본 배송비)과 같고,
 * 값은 주문 시점 값을 쓴다(요청서 #327 합의). 남은 금액은 묶음 전부 취소 판단과 같은 집계(처리 중 + 완료)로 센다.
 */
public final class RefundShippingFeePolicy {

    private RefundShippingFeePolicy() {
    }

    /**
     * @param target              판단할 요청 (요청됨 상태, 아직 집계에 들어 있지 않음)
     * @param bundle              주문 모듈이 알려 준 같은 판매자 묶음의 상품과 주문 시점 배송비 정보
     * @param cancelledQuantities 상품별 처리 중 + 완료 수량 (이번 요청 제외)
     * @param previouslyDeducted  같은 묶음의 처리 중 + 완료 요청에서 이미 뺀 배송비 합 (원)
     * @throws RestApiException 이번 요청까지 더해 주문 수량을 넘으면 {@code REFUND_QUANTITY_EXCEEDED},
     *                          빼야 할 배송비보다 취소 금액이 작으면 {@code PARTIAL_CANCEL_NOT_ALLOWED}
     */
    public static RefundShippingFee decide(Refund target, BundleShipping bundle, List<CancelledQuantity> cancelledQuantities,
                                           long previouslyDeducted) {
        if (RefundBundlePolicy.cancelsWholeBundle(target, bundle.items(), cancelledQuantities)) {
            // 앞 요청에서 뺀 배송비는 남은 상품이 배송된다고 보고 받은 돈인데, 결국 아무것도 배송되지 않으므로 돌려준다
            long shippingFee = Math.addExact(bundle.shippingFee(), previouslyDeducted);
            return new RefundShippingFee(shippingFee, bundle.remoteAreaFee(), 0L);
        }
        return new RefundShippingFee(0L, 0L, deductedShippingFee(target, bundle, cancelledQuantities));
    }

    // 일부 취소일 때만 부른다. 묶음 전부 취소에서 부르면 남은 금액이 0이 되어 배송비를 잘못 뺀다
    private static long deductedShippingFee(Refund target, BundleShipping bundle, List<CancelledQuantity> cancelledQuantities) {
        Long threshold = bundle.freeShippingThreshold();
        // 무료배송 기준이 없으면 처음부터 배송비를 냈으므로 깨질 것이 없다
        if (threshold == null) {
            return 0L;
        }
        long remainingBefore = remainingAmount(bundle.items(), cancelledQuantities);
        long remainingAfter = Math.subtractExact(remainingBefore, target.getItemAmount());
        if (remainingBefore < threshold || remainingAfter >= threshold) {
            return 0L;
        }
        // 같으면 0원 환불이 되지만 받아 준다(구매자는 취소한 상품 금액으로 배송비를 내는 셈). 지갑 호출은 Facade가 건너뛴다.
        // 작으면 모자란 배송비를 받을 방법이 없어 막는다. 그래서 남은 상품이 모두 배송비보다 싼 묶음은
        // 어떤 순서로 취소해도 전부 취소까지 가지 못할 수 있다(예: 2,500원 상품 21줄, 기준 5만 원, 배송비 3,000원).
        // 전부 취소는 묶음 전체 취소 요청(#365)으로 받는다.
        // TODO(논의 필요): 일부 취소에서 모자란 배송비를 구매자에게 추가 결제받는 흐름. 결제를 새로 받아야 해서 1단계에서는 막는다
        if (target.getItemAmount() < bundle.baseShippingFee()) {
            throw new RestApiException(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED);
        }
        return bundle.baseShippingFee();
    }

    // 이번 요청을 빼기 전, 아직 취소되지 않은 묶음 상품 금액 합
    private static long remainingAmount(List<BundleItem> items, List<CancelledQuantity> cancelledQuantities) {
        Map<Long, Long> cancelledByItem = new HashMap<>();
        for (CancelledQuantity cancelled : cancelledQuantities) {
            cancelledByItem.put(cancelled.orderItemId(), cancelled.quantity());
        }
        long remaining = 0L;
        for (BundleItem item : items) {
            long left = item.orderedQuantity() - cancelledByItem.getOrDefault(item.orderItemId(), 0L);
            remaining = Math.addExact(remaining, Math.multiplyExact(item.unitPrice(), left));
        }
        return remaining;
    }
}
