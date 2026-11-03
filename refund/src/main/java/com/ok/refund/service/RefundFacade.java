package com.ok.refund.service;

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
}
