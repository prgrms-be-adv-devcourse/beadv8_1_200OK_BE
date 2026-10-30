package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CreateOrderCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotBlank(message = "수령인 이름은 필수입니다.")
        @Size(max = 50, message = "수령인 이름은 50자 이하여야 합니다.")
        String recipientName,

        @NotBlank(message = "연락처는 필수입니다.")
        @Pattern(regexp = "^\\d{2,3}-\\d{3,4}-\\d{4}$", message = "연락처는 010-1234-5678 형식이어야 합니다.")
        String recipientPhone,

        @NotBlank(message = "우편번호는 필수입니다.")
        @Pattern(regexp = "^\\d{5}$", message = "우편번호는 숫자 5자리여야 합니다.")
        String zipCode,

        @NotBlank(message = "주소는 필수입니다.")
        @Size(max = 200, message = "주소는 200자 이하여야 합니다.")
        String address,

        @NotBlank(message = "상세 주소는 필수입니다.")
        @Size(max = 100, message = "상세 주소는 100자 이하여야 합니다.")
        String addressDetail,

        // 선택 항목 (예: 문 앞에 놓아 주세요)
        @Size(max = 255, message = "배송 요청사항은 255자 이하여야 합니다.")
        String deliveryNote,

        @AssertTrue(message = "판매자 정보 제공에 동의해야 주문할 수 있습니다.")
        boolean thirdPartyProvisionAgreed,

        @NotNull(message = "확인한 결제 금액은 필수입니다.")
        @PositiveOrZero(message = "결제 금액은 0 이상이어야 합니다.")
        Long expectedTotalAmount // 주문서 합계 + 도서산간 운임 (사용자가 화면에서 본 최종 금액)
) {
    // 공백만 보내면 요청사항이 없는 것으로 본다. 판매자 화면에 빈 요청사항이 찍히지 않게 null로 바꾼다
    public CreateOrderRequest {
        deliveryNote = (deliveryNote == null || deliveryNote.isBlank()) ? null : deliveryNote.strip();
    }

    public CreateOrderCommand toCommand(Long memberId, String idempotencyKey) {
        return new CreateOrderCommand(memberId, idempotencyKey, recipientName, recipientPhone,
                zipCode, address, addressDetail, deliveryNote, thirdPartyProvisionAgreed, expectedTotalAmount);
    }
}