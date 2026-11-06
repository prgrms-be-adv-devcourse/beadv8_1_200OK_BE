package com.ok.refund.application.port.in;

import org.springframework.data.domain.Page;

/**
 * 구매자가 본인 환불 요청 목록을 조회하는 입력 포트.
 */
public interface GetBuyerRefundsUseCase {

    /**
     * 구매자 본인의 환불 요청 목록을 최신순으로 25건씩 조회한다.
     *
     * @param page 0부터 시작하는 페이지 번호
     */
    Page<RefundResult> getBuyerRefunds(Long buyerId, int page);
}
