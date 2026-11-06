package com.ok.refund.application.service;

import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundShippingFeePolicy;
import com.ok.refund.domain.RefundStatus;

import java.util.List;

/**
 * 요청(미리 막기)과 승인(배송비 결정)이 함께 쓰는 배송비 판단을 한다.
 * 두 서비스에 따로 두면 "이미 취소된 것"으로 세는 상태가 한쪽만 바뀌어, 요청 때 통과한 요청이 승인 때 다른 기준으로 판단될 수 있어 한곳에 둔다.
 * 빈이 아니라 정적 도우미라 이 패키지 안에서만 쓴다. 트랜잭션은 부르는 서비스의 구간을 그대로 쓴다.
 */
final class RefundShippingFees {

    // 배송비 판단에서 "이미 취소된 것"으로 보는 상태. 승인된 요청은 되돌리지 않으므로 취소된 것으로 센다
    // TODO(#414): 완료 상태가 생기면 함께 센다. 처리 중을 완료로 바꾸는 처리도 승인처럼 묶음 잠금(lockBundleAndFind)을 거쳐야 한다
    // 묶음 잠금은 잠글 ID를 읽은 뒤 새로 들어온 요청을 잠그지 않는다. 그래도 되는 건 새 요청이 요청됨이고 요청됨은 여기서 세지 않기 때문이다.
    // 이 목록에 요청됨을 넣으면 그 전제가 깨져, 잠그지 않은 행이 집계를 바꿀 수 있다
    private static final List<RefundStatus> CANCELLING_STATUSES = List.of(RefundStatus.PROCESSING);

    private RefundShippingFees() {
    }

    // 같은 묶음(주문 + 판매자)에서 처리 중인 요청만 센다. 요청됨은 아직 승인될지 모르고, 거절·철회는 취소되지 않는다.
    // 판단할 환불은 요청됨이라 집계에 들어가지 않는다
    static RefundShippingFee decide(RefundPersistencePort refundPersistencePort, Refund refund, RefundableOrderItem orderItem) {
        List<CancelledQuantity> cancelled = refundPersistencePort.sumCancelledByOrderItem(
                refund.getOrderId(), refund.getSellerId(), CANCELLING_STATUSES);
        return RefundShippingFeePolicy.decide(refund, orderItem.bundle(), cancelled);
    }
}
