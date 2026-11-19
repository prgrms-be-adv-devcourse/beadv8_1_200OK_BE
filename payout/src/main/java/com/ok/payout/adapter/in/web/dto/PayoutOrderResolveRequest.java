package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutOrderResolveCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "잠기거나 멈춘 주문 해결 요청 (#568)")
public record PayoutOrderResolveRequest(

        // 255는 해결 기록 memo 컬럼 길이(VARCHAR(255))와 맞춤
        // @NotBlank 대신 @NotNull + @Size(min = 1) (#723): @Size(max)만 두면 Swagger 문서에 minLength 0으로 나와 폼이 빈 메모를 보내게 만들어짐
        //   공백만 보낸 값은 아래 생성자가 앞뒤 공백을 지워 빈 문자열이 되므로 @Size(min = 1)이 거절함 (@NotBlank와 같이 두면 오류가 두 번 나옴)
        @Schema(description = "해결 메모: 무엇을 확인·수정했는지 (필수, 공백만은 안 됨, 1~255자, 앞뒤 공백 제거)", example = "차감액을 9,000원으로 바로잡음",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "해결 메모는 필수입니다.")
        @Size(min = 1, max = 255, message = "해결 메모는 1자 이상 255자 이하여야 합니다.")
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
