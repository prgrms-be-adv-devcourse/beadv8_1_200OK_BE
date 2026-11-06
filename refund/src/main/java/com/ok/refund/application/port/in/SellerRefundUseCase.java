package com.ok.refund.application.port.in;

import org.springframework.data.domain.Page;

/**
 * 판매자가 본인에게 온 환불 요청을 조회하고 승인·거절하는 입력 포트.
 */
public interface SellerRefundUseCase {

    /**
     * 판매자에게 온 환불 요청 목록을 최신순으로 25건씩 조회한다.
     * 상태는 컨트롤러가 도메인 enum을 쓰지 않으려고 이름으로 받는다. 허용 값은 요청 DTO가 먼저 거른다.
     *
     * @param status 상태 이름. {@code null}이면 전체
     * @param page   0부터 시작하는 페이지 번호
     */
    Page<RefundResult> getSellerRefunds(Long sellerId, String status, int page);

    /**
     * 판매자가 본인 상품의 요청을 승인하거나 거절한다.
     * 승인은 같은 묶음의 배송비를 판단해야 해서 주문을 다시 조회하고, 거절은 주문을 부르지 않는다.
     *
     * @throws com.ok.common.exception.RestApiException 없거나 남의 요청이면 {@code REFUND_NOT_FOUND},
     *                                                  요청됨이 아니면 {@code INVALID_REFUND_STATE},
     *                                                  승인 때 주문 호출이 실패하면 {@code ORDER_API_FAILED}, 주문 상품이 없으면 {@code ORDER_ITEM_NOT_FOUND},
     *                                                  거절인데 사유가 비었으면 {@code REJECT_REASON_REQUIRED}
     */
    RefundResult decide(RefundDecisionCommand command);
}
