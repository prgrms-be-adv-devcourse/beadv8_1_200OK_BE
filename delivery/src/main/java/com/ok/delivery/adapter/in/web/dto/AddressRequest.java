package com.ok.delivery.adapter.in.web.dto;

import com.ok.delivery.application.dto.AddressCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AddressRequest(

        @NotBlank(message = "수령인은 필수입니다.")
        String recipientName,

        @NotBlank(message = "연락처는 필수입니다.")
        @Pattern(regexp = "^\\d(?:-?\\d){8,10}$", message = "연락처는 숫자 9~11자리여야 합니다.")
        String recipientPhone,

        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리여야 합니다.")
        String postalCode,

        @NotBlank(message = "주소는 필수입니다.")
        String address,

        @NotBlank(message = "상세 주소는 필수입니다.")
        String addressDetail,

        String deliveryNote
) implements AddressRequestSpec {

    public AddressCommand toCommand() {
        return new AddressCommand(
                recipientName,
                recipientPhone,
                postalCode,
                address,
                addressDetail,
                deliveryNote
        );
    }
}
