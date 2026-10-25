package com.ok.member.seller.service.dto;

/**
 * 관리자 판매자 신청 목록 조회 조건. 값은 요청 DTO에서 검증을 마친 상태다.
 *
 * @param status 신청 상태 이름. {@code null}이면 모든 상태를 조회한다.
 *               컨트롤러가 도메인 enum을 쓰지 않도록 문자열로 받고, 서비스가 enum으로 바꾼다
 * @param page   0부터 시작하는 페이지 번호
 */
public record SellerApplicationSearchQuery(
        String status,
        int page,
        int size
) {
}
