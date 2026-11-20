package com.ok.member.seller.application.dto;

/**
 * 판매자 프로필 조회 결과.
 *
 * @param originPostalCode 출고지 우편번호. 이 항목이 생기기 전에 등록한 프로필은 다음 수정 전까지 {@code null}
 * @param profileImageUrl  프로필 사진 URL. 사진이 없으면 {@code null}
 */
public record SellerProfileResult(
        String storeName,
        String representativeName,
        String customerServicePhone,
        String originPostalCode,
        String introduction,
        String profileImageUrl
) {
}
