package com.ok.member.seller.service.dto;

/**
 * 판매자 프로필 조회 결과.
 *
 * @param profileImageUrl 프로필 사진 URL. 사진이 없으면 {@code null}
 */
public record SellerProfileResult(
        String storeName,
        String representativeName,
        String customerServicePhone,
        String introduction,
        String profileImageUrl
) {
}
