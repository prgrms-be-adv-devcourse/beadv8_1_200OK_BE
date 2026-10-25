package com.ok.member.seller.service.dto;

/**
 * 판매자 신청 입력. 값은 요청 DTO에서 정규화·형식 검증을 마친 상태다.
 *
 * @param businessNumber 사업자등록번호 ({@code 000-00-00000})
 */
public record SellerApplyCommand(
        String storeName,
        String representativeName,
        String businessNumber,
        String mailOrderNumber
) {
}
