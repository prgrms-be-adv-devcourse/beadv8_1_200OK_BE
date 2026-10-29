package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 승인하는 요청이 판매자 묶음을 전부 취소하는지 판단한다. 전부 취소일 때만 배송비와 추가 운임을 돌려준다.
 *
 * <p>판단식: 묶음의 모든 상품에서 "처리 중 + 완료 수량(이번 요청 포함) == 주문 수량".
 * 이번 요청은 아직 요청됨이라 집계에 들어 있지 않으므로 여기서 한 번만 더한다. 이번 요청을 빠뜨리거나 두 번 세면
 * 둘 다 배송비가 누락되므로, 이 규칙을 한 곳에 둔다.
 * 같은 묶음의 승인은 한 줄로 세운 뒤 부르므로, 정확히 한 요청만 참이 된다.
 */
public final class RefundBundlePolicy {

    private RefundBundlePolicy() {
    }

    /**
     * @param target             승인하는 요청 (요청됨 상태)
     * @param bundleItems        주문 모듈이 알려 준 같은 판매자 묶음의 상품과 주문 수량
     * @param cancelledQuantities 잠근 뒤 다시 읽은 상품별 처리 중 + 완료 수량 (이번 요청 제외)
     * @return 이번 요청으로 묶음이 전부 취소되면 {@code true}
     * @throws IllegalStateException 주문 응답의 묶음 목록이 비었거나 이번 상품이 없거나 수량이 1 미만이면 (주문 모듈 응답 버그)
     * @throws RestApiException      이번 요청까지 더하면 주문 수량을 넘으면 {@code REFUND_QUANTITY_EXCEEDED}
     */
    public static boolean cancelsWholeBundle(Refund target, List<BundleItem> bundleItems, List<CancelledQuantity> cancelledQuantities) {
        Map<Long, Integer> orderedByItem = toOrderedQuantities(bundleItems, target.getOrderItemId());
        Map<Long, Long> cancelledByItem = new HashMap<>();
        for (CancelledQuantity cancelled : cancelledQuantities) {
            cancelledByItem.put(cancelled.orderItemId(), cancelled.quantity());
        }

        // 요청 때 남은 개수를 확인했어도, 앞 요청의 완료 커밋과 요청 저장이 엇갈리면 넘는 요청이 들어올 수 있다.
        // 같은 묶음 승인은 한 줄로 서 있으므로 여기가 마지막 방어선이다
        long targetCancelled = cancelledByItem.getOrDefault(target.getOrderItemId(), 0L) + target.getQuantity();
        if (targetCancelled > orderedByItem.get(target.getOrderItemId())) {
            throw new RestApiException(RefundErrorCode.REFUND_QUANTITY_EXCEEDED);
        }

        for (Map.Entry<Long, Integer> item : orderedByItem.entrySet()) {
            long counted = cancelledByItem.getOrDefault(item.getKey(), 0L);
            if (item.getKey().equals(target.getOrderItemId())) {
                counted += target.getQuantity();
            }
            if (counted != item.getValue()) {
                return false;
            }
        }
        return true;
    }

    // 주문 응답이 비정상이면 "반복할 상품이 없음 = 전부 취소"가 되어 배송비가 여러 번 나갈 수 있다. 판단 전에 막는다
    private static Map<Long, Integer> toOrderedQuantities(List<BundleItem> bundleItems, Long targetOrderItemId) {
        if (bundleItems == null || bundleItems.isEmpty()) {
            throw new IllegalStateException("판매자 묶음 상품 목록이 비어 있습니다.");
        }
        Map<Long, Integer> orderedByItem = new HashMap<>();
        for (BundleItem item : bundleItems) {
            if (item.orderItemId() == null || item.orderedQuantity() < 1) {
                throw new IllegalStateException("판매자 묶음 상품의 ID와 주문 수량(1 이상)은 필수입니다.");
            }
            orderedByItem.put(item.orderItemId(), item.orderedQuantity());
        }
        if (!orderedByItem.containsKey(targetOrderItemId)) {
            throw new IllegalStateException("판매자 묶음 상품 목록에 취소할 주문 상품이 없습니다. orderItemId=" + targetOrderItemId);
        }
        return orderedByItem;
    }
}
