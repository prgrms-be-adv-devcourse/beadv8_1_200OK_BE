package com.ok.member.seller.service.dto;

/**
 * 판매자 프로필 등록·수정 입력. 값은 요청 DTO에서 정규화·형식 검증을 마친 상태다.
 *
 * @param introduction    상점 소개. 없으면 {@code null}
 * @param profileImageUrl 미디어 업로드 API가 돌려준 프로필 사진 URL. 없으면 {@code null}
 */
public record SellerProfileCommand(
        String storeName,
        String representativeName,
        String customerServicePhone,
        String introduction,
        String profileImageUrl
) {
}
