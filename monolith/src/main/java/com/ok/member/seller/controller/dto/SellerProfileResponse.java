package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerProfileResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "판매자 프로필 응답")
public record SellerProfileResponse(

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

    public static SellerProfileResponse from(SellerProfileResult result) {
        return new SellerProfileResponse(
                result.storeName(),
                result.representativeName(),
                result.customerServicePhone(),
                result.introduction(),
                result.profileImageUrl()
        );
    }

}
