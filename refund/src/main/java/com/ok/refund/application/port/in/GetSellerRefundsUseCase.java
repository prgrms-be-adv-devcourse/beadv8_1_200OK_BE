package com.ok.refund.application.port.in;

import org.springframework.data.domain.Page;

/**
 * 판매자가 본인에게 온 환불 요청 목록을 조회하는 입력 포트.
 */
public interface GetSellerRefundsUseCase {

    /**
     * 판매자에게 온 환불 요청 목록을 최신순으로 25건씩 조회한다.
     * 상태는 컨트롤러가 도메인 enum을 쓰지 않으려고 이름으로 받는다. 허용 값은 요청 DTO가 먼저 거른다.
     *
     * @param status 상태 이름. {@code null}이면 전체
     * @param page   0부터 시작하는 페이지 번호
     */
    Page<RefundResult> getSellerRefunds(Long sellerId, String status, int page);
}
