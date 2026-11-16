package com.ok.member.seller.application.dto;

/**
 * 판매자 신청 입력. 값은 요청 DTO에서 정규화·형식 검증을 마친 상태다.
 *
 * @param businessNumber 사업자등록번호 ({@code 000-00-00000})
 * @param originPostalCode 출고지 우편번호 (5자리 숫자). 승인 때 배송 모듈에 보내는 처음 값
 */
public record SellerApplyCommand(
        String storeName,
        String representativeName,
        String businessNumber,
        String mailOrderNumber,
        String originPostalCode
) {
}
