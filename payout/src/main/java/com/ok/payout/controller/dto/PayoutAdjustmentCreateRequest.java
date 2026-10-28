package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutAdjustmentCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(description = "정산 수동 조정 요청 (지금은 막아 둔 API라 항상 501)")
public record PayoutAdjustmentCreateRequest(

        @Schema(description = "조정할 주문 항목 ID", example = "900")
        @NotNull(message = "주문 항목 ID는 필수입니다.")
        @Positive(message = "주문 항목 ID는 1 이상이어야 합니다.")
        Long orderItemId,

        // 허용 값은 PayoutEventType과 같아야 함. 컨트롤러 DTO는 domain enum을 쓸 수 없어 문자열 패턴으로 검증
        @Schema(description = "조정할 정산 항목 종류 (원래 정산후보와 같은 종류)", example = "SALE_AMOUNT",
                allowableValues = {"SALE_AMOUNT", "SALE_SHIPPING_FEE", "SALE_FEE"})
        @NotBlank(message = "정산 항목 종류는 필수입니다.")
        @Pattern(regexp = "SALE_AMOUNT|SALE_SHIPPING_FEE|SALE_FEE", message = "지원하지 않는 정산 항목 종류입니다.")
        String eventType,

        @Schema(description = "조정 금액 (원). 음수면 차감, 0은 불가", example = "-2000")
        @NotNull(message = "조정 금액은 필수입니다.")
        Long amount,

        // 255는 정산후보 reason 컬럼 길이(VARCHAR(255))와 맞춤
        @Schema(description = "조정 사유 (255자 이하, 앞뒤 공백 제거)", example = "금액 오입력 정정")
        @NotBlank(message = "조정 사유는 필수입니다.")
        @Size(max = 255, message = "조정 사유는 255자 이하여야 합니다.")
        String reason
) {

    /**
     * 요청을 역직렬화하는 시점에 조정 사유의 앞뒤 공백을 제거한다. 검증은 정리된 값을 기준으로 수행된다.
     */
    public PayoutAdjustmentCreateRequest {
        reason = reason == null ? null : reason.strip();
    }

    // 조정 금액은 음수(차감)는 허용하고 0만 막아야 함. 기본 검증(@Positive 등)으로는 "0만 거절"을 표현할 수 없어서
    // 메서드 결과가 true여야 통과하는 @AssertTrue로 검증 (0원 조정은 장부에 의미 없는 줄만 남김, #65 리뷰 후속)
    // is로 시작하는 메서드는 Swagger가 요청 필드로 오해해 문서에 나오므로 숨김
    // amount가 null이면 @NotNull이 따로 잡으므로 여기서는 통과시켜 오류 메시지가 두 번 나오지 않게 함
    @Schema(hidden = true)
    @AssertTrue(message = "조정 금액은 0원일 수 없습니다.")
    public boolean isAmountNotZero() {
        return amount == null || amount != 0;
    }

    public PayoutAdjustmentCreateCommand toCommand() {
        return new PayoutAdjustmentCreateCommand(orderItemId, eventType, amount, reason);
    }
}
