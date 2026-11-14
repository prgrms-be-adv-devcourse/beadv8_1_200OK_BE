package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutBlockedOrderCauseResult;
import com.ok.payout.application.port.in.PayoutBlockedOrderResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "막힌 주문 1건 (잠김·멈춤·해결됨)")
public record PayoutBlockedOrderResponse(

        @Schema(description = "주문 ID. 해결은 POST /api/v1/admin/payouts/orders/{orderId}/resolution", example = "100")
        Long orderId,

        @Schema(description = "상태. LOCKED 잠김(수수료 불일치·결과 검증 불일치, 다시 검사해도 안 풀림), "
                + "STOPPED 멈춤(성공한 실행에서 3번 실패), RESOLVED 해결됨(다음 정산 실행이 끝나면 목록에서 빠지거나, 다시 막혔으면 LOCKED·STOPPED로 바뀜)",
                example = "LOCKED", allowableValues = {"LOCKED", "STOPPED", "RESOLVED"})
        String status,

        @Schema(description = "성공한 실행에서 해결 안 된 실패가 남은 실행 수. 멈춤(3번) 판정용이며, 잠김은 이 값과 상관없음(0·1일 수 있음)",
                example = "1")
        int failedRunCount,

        @Schema(description = "해결 안 된 실패 기록 중 가장 늦은 시각", example = "2026-11-14T10:00:00")
        LocalDateTime lastFailedAt,

        @Schema(description = "원인에 나온 판매자 회원 ID (중복 없음)", example = "[7]")
        List<Long> sellerIds,

        @Schema(description = "원인에 나온 실패 사유 (중복 없음)", example = "[\"FEE_MISMATCH\"]")
        List<String> reasons,

        @Schema(description = "원인에 나온 원인 범주 (중복 없음)", example = "[\"SYSTEM\"]")
        List<String> categories,

        @Schema(description = "원인 목록 (같은 기록은 1번만, 실패 시각 최신순). 상세는 내부 검사 내용이라 관리자 화면에만 보여 줌")
        List<PayoutBlockedOrderCauseResponse> causes,

        @Schema(description = "막힌 순간의 초과 청구 근거: 그 막힘을 만든 금액 검사 때 계산한 값(실패 상세 글과 같음). "
                + "원인에 초과 청구가 없거나 계산할 수 없으면 null. 예전 막힘의 값은 해결 기록 조회의 상세 글로 봄. "
                + "RESOLVED 줄은 해결 뒤 다시 실패했으면 그 실패 때 값으로 바뀌어 있을 수 있음(다음 정산 실행이 끝나면 정리됨)",
                nullable = true)
        PayoutOverchargeEvidenceResponse evidenceAtBlock,

        @Schema(description = "지금의 초과 청구 근거: 목록을 볼 때 계산한 값. 막힌 순간 값과 비교해 데이터가 바뀌었는지 봄. "
                + "원인에 초과 청구가 없거나 계산할 수 없으면 null", nullable = true)
        PayoutOverchargeEvidenceResponse evidenceNow
) {

    public static PayoutBlockedOrderResponse from(PayoutBlockedOrderResult result) {
        List<PayoutBlockedOrderCauseResponse> causes = new ArrayList<>();
        for (PayoutBlockedOrderCauseResult cause : result.causes()) {
            causes.add(PayoutBlockedOrderCauseResponse.from(cause));
        }
        return new PayoutBlockedOrderResponse(result.orderId(), result.status(), result.failedRunCount(),
                result.lastFailedAt(), result.sellerIds(), result.reasons(), result.categories(), causes,
                PayoutOverchargeEvidenceResponse.from(result.evidenceAtBlock()),
                PayoutOverchargeEvidenceResponse.from(result.evidenceNow()));
    }
}
