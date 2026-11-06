package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.in.RefundRequestCommand;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.RequestRefundUseCase;
import com.ok.refund.application.port.out.OrderQueryPort;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.exception.RefundErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자의 취소 요청을 "요청됨"으로 저장한다. 요청만으로는 주문·배송·지갑·상품이 바뀌지 않는다.
 * 주문 호출을 트랜잭션 안에서 하면 주문이 느릴 때 DB 연결도 그만큼 잡고 있게 되어, 주문 조회는 구간 밖에서 하고
 * 확인과 저장만 짧은 구간에서 한다.
 */
@Service
public class RequestRefundService implements RequestRefundUseCase {

    private final OrderQueryPort orderQueryPort;
    private final RefundPersistencePort refundPersistencePort;
    private final TransactionTemplate transaction;

    public RequestRefundService(OrderQueryPort orderQueryPort, RefundPersistencePort refundPersistencePort,
                                PlatformTransactionManager transactionManager) {
        this.orderQueryPort = orderQueryPort;
        this.refundPersistencePort = refundPersistencePort;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 주문 상품 정보를 받아 환불 요청을 저장한다.
     * 같은 주문 상품에 진행 중인 요청이 있으면 {@code active_order_item_id} UNIQUE가 막는다.
     * 먼저 조회해 확인하지 않는 이유: 두 요청이 동시에 오면 둘 다 "없음"으로 보고 통과할 수 있어, DB 제약이 마지막에 막아야 한다.
     *
     * @throws RestApiException 주문 상품이 없거나 남의 주문이면 {@code ORDER_ITEM_NOT_FOUND}, 주문 호출이 실패하면 {@code ORDER_API_FAILED},
     *                          출고 전이 아니면 {@code ORDER_ITEM_NOT_CANCELLABLE},
     *                          산 개수보다 많으면 {@code REFUND_QUANTITY_EXCEEDED}, 개수가 1 미만이면 {@code INVALID_REFUND_QUANTITY},
     *                          진행 중인 요청이 있으면 {@code REFUND_ALREADY_IN_PROGRESS},
     *                          무료배송 기준이 깨져 뺄 배송비보다 취소 금액이 작으면 {@code PARTIAL_CANCEL_NOT_ALLOWED}
     */
    @Override
    public RefundResult request(RefundRequestCommand command) {
        // 주문 조회는 구간 밖에서 한다(모듈 규칙 3절). 느린 주문 때문에 DB 연결을 오래 잡지 않으려고
        RefundableOrderItem orderItem = orderQueryPort.getOrderItem(command.orderId(), command.orderItemId());
        return transaction.execute(status -> saveRequest(command, orderItem));
    }

    private RefundResult saveRequest(RefundRequestCommand command, RefundableOrderItem orderItem) {
        // 남의 주문은 "있지만 네 것이 아님"을 알려 주지 않으려고 없는 것과 같게 응답한다
        if (!orderItem.buyerId().equals(command.buyerId())) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_FOUND);
        }
        if (!orderItem.isCancellable()) {
            throw new RestApiException(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE);
        }
        // TODO(#414): 완료 상태가 생기면 이미 취소가 끝난 개수를 빼고 비교한다. 지금은 완료가 없어 뺄 값이 항상 0이다
        if (command.quantity() > orderItem.quantity()) {
            throw new RestApiException(RefundErrorCode.REFUND_QUANTITY_EXCEEDED);
        }
        // 단가·판매자·옵션·이름은 구매자 입력이 아니라 주문에서 받은 값을 쓴다
        Refund refund = Refund.request(orderItem.orderId(), orderItem.orderItemId(), command.quantity(), command.buyerId(),
                orderItem.sellerId(), orderItem.productOptionId(), orderItem.productName(), orderItem.optionName(),
                orderItem.unitPrice(), command.cancelReason());
        Refund saved = refundPersistencePort.saveNew(refund);
        // 배송비는 승인 때 정하지만, 지금 승인해도 받을 수 없는 요청은 판매자에게 보내지 않고 미리 막는다.
        // 저장 뒤에 판단하는 이유: 같은 상품에 처리 중 요청이 있으면 그 개수까지 더해져 판단이 "개수 초과"(500)로 깨지므로,
        // UNIQUE가 먼저 409로 막게 한다. 여기서 던지면 트랜잭션이 되돌려져 저장한 행도 남지 않는다
        RefundShippingFee fee = RefundShippingFees.decide(refundPersistencePort, saved, orderItem);
        if (!fee.allowed()) {
            throw new RestApiException(RefundErrorCode.PARTIAL_CANCEL_NOT_ALLOWED);
        }
        return RefundResult.from(saved);
    }
}
