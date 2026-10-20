package com.ok.payout.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.payout.controller.dto.PayoutAdjustmentCreateRequest;
import com.ok.payout.controller.dto.PayoutDetailResponse;
import com.ok.payout.controller.dto.PayoutItemResponse;
import com.ok.payout.controller.dto.PayoutItemSearchRequest;
import com.ok.payout.controller.dto.PayoutPageResponse;
import com.ok.payout.controller.dto.PayoutRunResponse;
import com.ok.payout.controller.dto.PayoutSearchRequest;
import com.ok.payout.controller.dto.PayoutSummaryResponse;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.payout.service.PayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 정산 API (조회, 수동 실행, 수동 조정). 판매자용은 PayoutController (#198에서 분리)
// 개발 중 수동 실행용 API가 있어 prod에서는 등록하지 않음 (prod 정산은 스케줄러로만 실행)
// 토큰 인증은 붙었지만 SecurityConfig가 아직 전환 기간(permitAll)이라 prod에는 열지 않음 (기본 거부 전환은 #108)
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/admin/payouts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PayoutAdminController implements PayoutAdminControllerSpec {

    private final PayoutJobRunner payoutJobRunner;
    private final PayoutService payoutService;

    // 정산 목록 조회 (모든 정산. payeeId·system으로 거를 수 있음)
    @Override
    @GetMapping
    public ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            @Valid @ModelAttribute PayoutSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.searchForAdmin(request.toQuery()).map(PayoutSummaryResponse::from)));
    }

    // 정산 상세 조회. 판매자 정산이면 그 판매자의 수수료 합계와 시스템 몫 정산 ID도 함께 보여 줌
    // 항목 목록은 /{payoutId}/items에서 따로 조회
    @Override
    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutDetailResponse> getDetail(@PathVariable Long payoutId) {
        return ResponseEntity.ok(PayoutDetailResponse.from(payoutService.getDetailForAdmin(payoutId)));
    }

    // 정산 항목 조회. 항목마다 정산후보 생성 시각과, 조정 건이면 조정 정보(번호, 관리자 ID, 사유)를 함께 보여 줌
    @Override
    @GetMapping("/{payoutId}/items")
    public ResponseEntity<PayoutPageResponse<PayoutItemResponse>> getItems(
            @PathVariable Long payoutId,
            @Valid @ModelAttribute PayoutItemSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.getItemsForAdmin(payoutId, request.toQuery()).map(PayoutItemResponse::from)));
    }

    // 정산 전체 수동 실행 (개발 중 수동 실행용. 실패 응답은 #67에서 처리)
    @Override
    @PostMapping("/run")
    public ResponseEntity<PayoutRunResponse> run() throws Exception {
        JobExecution execution = payoutJobRunner.run();
        // 배치 실패는 사용자 입력이 아니라 시스템 결과라 표준 예외로 던짐 (exception.md: 값의 출처 기준)
        // 공통 처리기가 500으로 응답하고 실행 ID·상태가 담긴 메시지는 서버 로그(ERROR)에만 남김 (응답 detail에는 나가지 않음)
        // TODO(#67): 임시로 실패 여부만 알림. 정산 실패 기록이 생기면 무엇이 실패했는지와 실패 기록 ID를 함께 응답하도록 변경
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "정산 실행에 실패했습니다. executionId=" + execution.getId() + ", status=" + execution.getStatus());
        }
        return ResponseEntity.ok(new PayoutRunResponse(execution.getId(), execution.getStatus().name()));
    }

    // 정산 1건 수동 실행. 형태만 공개하고 지금은 막아 둠 (막아 둔 이유는 PayoutService.runOrderItem 참고)
    // (임시 헤더 X-Member-Role로 역할을 받던 방식은 헤더만 바꾸면 관리자인 척할 수 있어 토큰으로 전환함, #228)
    @Override
    @PostMapping("/run/order-items/{orderItemId}")
    public ResponseEntity<Void> runOrderItem(@PathVariable Long orderItemId) {
        payoutService.runOrderItem(orderItemId);
        return ResponseEntity.noContent().build();
    }

    // 정산 수동 조정. 형태만 공개하고 지금은 막아 둠 (막아 둔 이유는 PayoutService.createAdjustment 참고)
    // 조정 기능을 열면 201과 생성된 정산후보 ID를 응답할 예정
    @Override
    @PostMapping("/adjustments")
    public ResponseEntity<Void> createAdjustment(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody PayoutAdjustmentCreateRequest request
    ) {
        payoutService.createAdjustment(memberId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
