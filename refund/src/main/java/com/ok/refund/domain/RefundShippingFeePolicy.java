package com.ok.refund.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 환불 한 건에서 배송비를 돌려줄지, 뺄지, 이 취소를 받을 수 있는지 판단한다.
 * 무료배송 기준은 배송 모듈의 배송비 규칙과 같게 "남은 상품 금액 합 >= 기준이면 무료"로 본다. 값은 주문 시점 값을 쓴다.
 *
 * <ul>
 *   <li>이번 취소로 판매자 묶음이 전부 취소되면: 묶음 배송비 + 앞선 환불에서 뺀 배송비와 도서산간 운임을 돌려준다. 상자가 나가지 않으니 받을 근거가 없다</li>
 *   <li>일부 취소로 남은 묶음 금액이 무료배송 기준 아래로 처음 내려가면: 기본 배송비를 뺀다. 판매자가 남은 상품의 택배비를 떠안지 않게 하려는 것이다</li>
 *   <li>그때 취소 금액이 기본 배송비보다 작으면: 받을 수 없다(돌려줄 금액이 음수가 된다)</li>
 *   <li>그 밖(기준 없음, 구매자가 배송비를 냄, 처음부터 기준 아래, 취소 뒤에도 기준 이상): 0</li>
 * </ul>
 */
public final class RefundShippingFeePolicy {

    // 배송비 판단에서 "이미 취소된 것"으로 보는 상태. 승인된 요청은 되돌리지 않으므로 처리 중이든 완료든 취소된 것으로 센다.
    // 완료를 빼면 끝난 환불이 개수에서 빠져, 같은 묶음의 다음 환불이 "아직 남아 있다"로 보고 배송비를 또 빼거나 못 돌려준다(#414)
    // 요청(미리 막기)과 승인(배송비 결정)이 같은 기준을 써야 해서 이 정책에 둔다. 두 서비스에 따로 두면 한쪽만 바뀌어
    // 요청 때 통과한 요청이 승인 때 다른 기준으로 판단될 수 있다
    // 처리 중을 완료로 바꿀 때는 그 환불 행만 잠근다. 두 상태가 모두 이 목록에 있어 바꿔도 집계가 변하지 않기 때문이다.
    // 처리 중을 거절로 바꿀 때(승인 뒤 주문이 취소 반영을 거절)는 집계가 바뀌는데도 그 환불 행만 잠근다.
    // 그래서 같은 묶음의 다른 환불이 그 사이 승인되면 이 환불을 "취소된 것"으로 보고 배송비를 정하고, 이 환불이 거절된 뒤에도
    // 그 판단이 틀린 채 남을 수 있다. 예: 무료배송 기준 50,000원·기본 배송비 3,000원 묶음에 30,000원 상품 A·B가 있을 때
    // A가 처리 중이면 B 승인은 묶음 전부 취소로 보고 배송비를 빼지 않는다. 그 뒤 A가 거절되면 A가 남으므로 B에서 3,000원을 뺐어야 한다.
    // TODO(#516): 묶음을 함께 잠그고 같은 묶음에 처리 중·완료된 다른 환불이 있으면 드러내거나 되돌리기(#546)로 맞출지 정한다
    // 묶음 잠금은 잠글 ID를 읽은 뒤 새로 들어온 요청을 잠그지 않는다. 그래도 되는 건 새 요청이 요청됨이고 요청됨은 여기서 세지 않기 때문이다.
    // 이 목록에 요청됨을 넣으면 그 전제가 깨져, 잠그지 않은 행이 집계를 바꿀 수 있다
    public static final List<RefundStatus> CANCELLING_STATUSES = List.of(RefundStatus.PROCESSING, RefundStatus.COMPLETED);

    private RefundShippingFeePolicy() {
    }

    /**
     * 이번 환불의 배송비를 판단한다.
     *
     * @param target    판단할 환불. 상품 금액과 개수를 쓴다
     * @param bundle    주문 모듈이 알려 준 판매자 묶음 값
     * @param cancelled 같은 묶음에서 이미 취소가 진행 중인 개수와 뺀 배송비 (이번 환불은 빼고)
     * @throws IllegalStateException 묶음에 이번 상품이 없거나, 진행 중 개수가 묶음과 맞지 않으면. 주문 값과 환불 기록이 어긋난 버그라 500으로 드러낸다
     */
    public static RefundShippingFee decide(Refund target, BundleShipping bundle, List<CancelledQuantity> cancelled) {
        Map<Long, Long> cancelledBefore = cancelledByOrderItem(bundle, cancelled);
        if (!cancelledBefore.containsKey(target.getOrderItemId())) {
            throw new IllegalStateException("주문 묶음에 환불할 주문 상품이 없습니다. orderItemId=" + target.getOrderItemId());
        }

        // 앞선 취소를 뺀 남은 묶음 금액(이번 취소 전)과, 이번까지 취소하면 모든 상품이 다 취소되는지 함께 본다
        long remainingBefore = 0;
        boolean allCancelled = true;
        for (BundleItem item : bundle.items()) {
            long before = cancelledBefore.get(item.orderItemId());
            long after = item.orderItemId().equals(target.getOrderItemId()) ? before + target.getQuantity() : before;
            if (after > item.orderedQuantity()) {
                throw new IllegalStateException("취소 개수 합이 주문 개수를 넘습니다. orderItemId=" + item.orderItemId());
            }
            if (after < item.orderedQuantity()) {
                allCancelled = false;
            }
            remainingBefore = Math.addExact(remainingBefore,
                    Math.multiplyExact(item.unitPrice(), item.orderedQuantity() - before));
        }

        if (allCancelled) {
            return RefundShippingFee.refund(Math.addExact(bundle.shippingFee(), previouslyDeducted(cancelled)),
                    bundle.remoteAreaFee());
        }

        Long threshold = bundle.freeShippingThreshold();
        // 기준이 없으면 처음부터 배송비를 냈고, 이미 기준 아래면 배송비를 냈거나 앞선 환불에서 뺐다. 어느 쪽이든 이번에 더 뺄 것이 없다.
        // 구매자가 배송비를 냈으면(shippingFee > 0) 무료배송 묶음이 아니었다. 단가 합이 기준 이상으로 보여도(주문 쪽 계산이 할인 등으로
        // 달라진 경우) 이미 낸 배송비를 한 번 더 빼지 않도록, 낸 배송비를 기준 판단보다 먼저 믿는다
        if (threshold == null || bundle.shippingFee() > 0 || remainingBefore < threshold) {
            return RefundShippingFee.none();
        }
        long remainingAfter = remainingBefore - target.getItemAmount();
        if (remainingAfter >= threshold) {
            return RefundShippingFee.none();
        }
        // 취소 금액 == 기본 배송비면 0원 환불이지만 받는다. 구매자는 상품을 안 받고 판매자는 택배비를 받아 손해 보는 사람이 없다
        if (target.getItemAmount() < bundle.baseShippingFee()) {
            return RefundShippingFee.notAllowed();
        }
        return RefundShippingFee.deduct(bundle.baseShippingFee());
    }

    // 묶음이 결국 전부 취소되면 상자가 나가지 않으므로 앞선 환불에서 뺀 배송비도 마지막 환불에서 돌려준다
    private static long previouslyDeducted(List<CancelledQuantity> cancelled) {
        long sum = 0;
        for (CancelledQuantity each : cancelled) {
            sum = Math.addExact(sum, each.deductedShippingFeeAmount());
        }
        return sum;
    }

    // 묶음의 모든 상품을 0으로 채운 뒤 진행 중 개수를 더한다. 묶음에 없는 상품의 진행 중 요청은 주문 값과 환불 기록이 어긋난 것이다
    private static Map<Long, Long> cancelledByOrderItem(BundleShipping bundle, List<CancelledQuantity> cancelled) {
        Map<Long, Long> result = new HashMap<>();
        for (BundleItem item : bundle.items()) {
            result.put(item.orderItemId(), 0L);
        }
        for (CancelledQuantity each : cancelled) {
            Long current = result.get(each.orderItemId());
            if (current == null) {
                throw new IllegalStateException("주문 묶음에 없는 상품의 진행 중 환불이 있습니다. orderItemId=" + each.orderItemId());
            }
            result.put(each.orderItemId(), current + each.quantity());
        }
        return result;
    }
}
