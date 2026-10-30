package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.PostalRegionUpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "도서산간 우편번호 수정 요청")
public record PostalRegionUpdateRequest(

        @Schema(description = "지역 그룹 코드 (GENERAL 제외)", example = "JEJU_ISLANDS")
        @NotBlank(message = "지역 그룹은 필수입니다.")
        String regionGroup,

        @Schema(description = "추가 소요일 (영업일, 0 이상)", example = "2")
        @NotNull(message = "추가 소요일은 필수입니다.")
        @Min(value = 0, message = "추가 소요일은 0 이상이어야 합니다.")
        Integer extraDeliveryDays
) {

    public PostalRegionUpdateCommand toCommand(String postalCode) {
        return new PostalRegionUpdateCommand(postalCode, regionGroup, extraDeliveryDays);
    }
}
