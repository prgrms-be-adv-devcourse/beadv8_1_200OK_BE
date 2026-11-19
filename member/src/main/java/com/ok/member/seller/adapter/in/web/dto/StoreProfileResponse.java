package com.ok.member.seller.adapter.in.web.dto;

import com.ok.member.seller.application.dto.SellerProfileResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 누구나 보는 상점 프로필 응답.
 * 출고지 우편번호는 배송비 계산용 내부 정보라 판매자 본인 응답({@link SellerProfileResponse})에만 담는다.
 */
@Schema(description = "상점 프로필 응답 (상점·상품 상세 화면용)")
public record StoreProfileResponse(

        @Schema(description = "판매자 ID (상품의 sellerId)", example = "7")
        Long sellerId,

        @Schema(description = "상호명")
        String storeName,

        @Schema(description = "대표자명")
        String representativeName,

        @Schema(description = "고객센터 연락처", example = "054-873-2210")
        String customerServicePhone,

        @Schema(description = "상점 소개. 없으면 null", nullable = true)
        String introduction,

        @Schema(description = "프로필 사진 URL. 없으면 null이고, 화면에서 상호 첫 글자를 보여 준다", nullable = true)
        String profileImageUrl
) {

    public static StoreProfileResponse from(Long sellerId, SellerProfileResult result) {
        return new StoreProfileResponse(
                sellerId,
                result.storeName(),
                result.representativeName(),
                result.customerServicePhone(),
                result.introduction(),
                result.profileImageUrl()
        );
    }

}
