package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundResult;
import com.ok.refund.service.dto.RefundableOrderItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 다른 모듈 호출과 환불 DB 작업의 순서를 맞춘다. 트랜잭션을 걸지 않는다.
 * 주문 호출을 트랜잭션 안에서 하면 주문이 느릴 때 DB 연결도 그만큼 잡고 있게 되어, 호출은 여기서 하고 저장만 서비스에 맡긴다.
 */
@Component
@RequiredArgsConstructor
public class RefundFacade {

    private final RefundOrderClient orderClient;
    private final RefundService refundService;

    /**
     * 주문 상품 정보를 받아 환불 요청을 저장한다.
     *
     * @throws com.ok.common.exception.RestApiException 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  주문 호출이 실패하면 {@code ORDER_API_FAILED}, 그 밖은 {@link RefundService#request}를 따른다
     */
    public RefundResult request(RefundRequestCommand command) {
        RefundableOrderItem orderItem = orderClient.getOrderItem(command.orderId(), command.orderItemId());
        return refundService.request(command, orderItem);
    }

    /**
     * 판매자가 본인 상품의 요청을 승인하거나 거절한다.
     * 승인은 같은 묶음의 배송비를 판단해야 해서 주문을 다시 조회한다. 순서: 환불을 읽어 주문 ID를 얻음 → 주문 조회(트랜잭션 밖)
     * → 잠그고 판단·저장. 앞의 두 단계 사이에 철회되면 마지막 단계가 바뀐 상태를 보고 막는다.
     * 거절은 쓸 주문 값이 없어 주문을 부르지 않는다. 주문이 느리거나 장애여도 거절은 막히지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND},
     *                                                  요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                                                  승인 때 주문 호출이 실패하면 {@code ORDER_API_FAILED}, 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  승인 때 이미 출고됐으면 {@code ORDER_ITEM_NOT_CANCELLABLE},
     *                                                  거절인데 사유가 비었으면 {@code REJECT_REASON_REQUIRED}
     */
    public RefundResult decide(RefundDecisionCommand command) {
        // 예전 방식 enum switch는 컴파일러가 모든 값을 다뤘는지 보지 않아, 결정이 늘었는데 여기를 빠뜨리면 아무것도 바꾸지 않고
        // 성공으로 응답한다. case null을 두면 새 방식 switch가 되어 모든 값을 다루지 않으면 컴파일 오류가 난다(default를 두지 않는 이유).
        // null은 요청 DTO가 막으므로 여기 오면 버그다
        return switch (command.decision()) {
            case APPROVE -> approve(command.refundId(), command.sellerId());
            case REJECT -> refundService.reject(command.refundId(), command.sellerId(), command.rejectReason());
            case null -> throw new IllegalArgumentException("환불 결정은 필수입니다.");
        };
    }

    private RefundResult approve(Long refundId, Long sellerId) {
        RefundResult refund = refundService.getSellerRefund(refundId, sellerId);
        // 이미 끝난 요청이면 주문을 부르지 않고 바로 409로 돌려준다. 주문 장애 때 409 대신 503이 나가지 않게 하려는 것이다.
        // 최종 확인은 아니다. 이 확인과 잠금 사이에 철회될 수 있어 서비스가 잠근 뒤 다시 본다
        if (!RefundStatus.REQUESTED.name().equals(refund.status())) {
            throw new RestApiException(RefundErrorCode.INVALID_REFUND_STATE);
        }
        RefundableOrderItem orderItem = orderClient.getOrderItem(refund.orderId(), refund.orderItemId());
        return refundService.approve(refundId, sellerId, orderItem);
    }
}
