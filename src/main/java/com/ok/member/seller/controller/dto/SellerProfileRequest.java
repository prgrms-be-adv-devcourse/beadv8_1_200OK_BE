package com.ok.member.seller.controller.dto;

import com.ok.member.seller.service.dto.SellerProfileCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "판매자 프로필 등록·수정 요청. 수정은 전체 값을 바꾼다")
public record SellerProfileRequest(

        @Schema(description = "상호명 (50자 이하, 앞뒤 공백 제거)", example = "청송 햇살농원")
        @NotBlank(message = "상호명은 필수입니다.")
        @Size(max = 50, message = "상호명은 50자 이하여야 합니다.")
        String storeName,

        @Schema(description = "대표자명 (50자 이하, 앞뒤 공백 제거)", example = "김영수")
        @NotBlank(message = "대표자명은 필수입니다.")
        @Size(max = 50, message = "대표자명은 50자 이하여야 합니다.")
        String representativeName,

        @Schema(description = "고객센터 연락처 (하이픈 포함 전화번호)", example = "054-873-2210")
        @NotBlank(message = "고객센터 연락처는 필수입니다.")
        @Pattern(regexp = "0\\d{1,2}-\\d{3,4}-\\d{4}|1\\d{3}-\\d{4}",
                message = "고객센터 연락처는 054-873-2210, 1588-0000 같은 형식이어야 합니다.")
        String customerServicePhone,

        @Schema(description = "상점 소개 (200자 이하, 선택). 상점과 상품 상세의 생산 정보 아래에 보인다",
                example = "3대째 사과만 키우는 청송 부남면 과수원입니다.", nullable = true)
        @Size(max = 200, message = "상점 소개는 200자 이하여야 합니다.")
        String introduction,

        @Schema(description = "프로필 사진 URL (선택). POST /api/v1/media/SELLER_PROFILE 응답의 url을 보낸다. "
                + "null이면 사진이 없고(수정 시 사진 삭제), 화면에서 상호 첫 글자를 보여 준다",
                example = "https://bucket.s3.ap-northeast-2.amazonaws.com/seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg",
                nullable = true)
        String profileImageUrl
) {

    /**
     * 요청을 역직렬화하는 시점에 값을 정규화한다. 검증은 정규화된 값을 기준으로 수행된다.
     * 상점 소개와 사진 URL은 선택 값이라, 비어 있으면 없는 값({@code null})으로 통일한다.
     */
    public SellerProfileRequest {
        storeName = trim(storeName);
        representativeName = trim(representativeName);
        customerServicePhone = trim(customerServicePhone);
        introduction = trimToNull(introduction);
        profileImageUrl = trimToNull(profileImageUrl);
    }

    public SellerProfileCommand toCommand() {
        return new SellerProfileCommand(storeName, representativeName, customerServicePhone,
                introduction, profileImageUrl);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

}
