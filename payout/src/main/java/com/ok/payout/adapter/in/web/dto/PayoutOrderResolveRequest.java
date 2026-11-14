package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "잠기거나 멈춘 주문 해결 요청 (#568)")
public record PayoutOrderResolveRequest(

        // 255는 해결 기록 memo 컬럼 길이(VARCHAR(255))와 맞춤
        @Schema(description = "해결 메모: 무엇을 확인·수정했는지 (255자 이하, 앞뒤 공백 제거)", example = "차감액을 9,000원으로 바로잡음")
        @NotBlank(message = "해결 메모는 필수입니다.")
        @Size(max = 255, message = "해결 메모는 255자 이하여야 합니다.")
        String memo
) {

    /**
     * 요청을 역직렬화하는 시점에 메모의 앞뒤 공백을 제거한다. 검증은 정리된 값을 기준으로 수행된다.
     */
    public PayoutOrderResolveRequest {
        memo = memo == null ? null : memo.strip();
    }

    public PayoutOrderResolveCommand toCommand(Long orderId, Long adminId) {
        return new PayoutOrderResolveCommand(orderId, adminId, memo);
    }
}
