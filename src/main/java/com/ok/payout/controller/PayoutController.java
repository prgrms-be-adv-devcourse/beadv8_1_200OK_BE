package com.ok.payout.controller;

import com.ok.common.exception.RestApiException;
import com.ok.payout.controller.dto.PayoutAdjustmentCreateRequest;
import com.ok.payout.controller.dto.PayoutDetailResponse;
import com.ok.payout.controller.dto.PayoutItemResponse;
import com.ok.payout.controller.dto.PayoutItemSearchRequest;
import com.ok.payout.controller.dto.PayoutPageResponse;
import com.ok.payout.controller.dto.PayoutRunResponse;
import com.ok.payout.controller.dto.PayoutSearchRequest;
import com.ok.payout.controller.dto.PayoutSummaryResponse;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.service.PayoutJobRunner;
import com.ok.payout.service.PayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 개발 중 수동 실행용 임시 API. 인증 도입 전까지 prod에서는 등록하지 않음 (prod 정산은 스케줄러로만 실행)
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    // TODO(#52): 임시 헤더. 로그인(#98)은 JWT 발급만 하고 요청마다 토큰을 검증하는 부분이 아직 연결되지 않아 회원 정보를 꺼낼 수 없음
    // 클라이언트가 아무 회원으로나 위장할 수 있어 보안 수단이 아님(그래서 prod 미등록). 인증이 붙으면 principal로 바꾸고 권한 판단 로직은 그대로 둠
    static final String MEMBER_ID_HEADER = "X-Member-Id";
    static final String MEMBER_ROLE_HEADER = "X-Member-Role";

    private final PayoutJobRunner payoutJobRunner;
    private final PayoutService payoutService;

    // 정산 전체 수동 실행 (개발 중 수동 실행용. 권한은 #52, 실패 응답은 #67에서 처리)
    @PostMapping("/run")
    public ResponseEntity<PayoutRunResponse> run() throws Exception {
        JobExecution execution = payoutJobRunner.run();
        // TODO(#67): 임시로 실패 여부만 알림. 정산 실패 기록이 생기면 무엇이 실패했는지와 실패 기록 ID를 함께 응답하도록 변경
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new RestApiException(PayoutErrorCode.PAYOUT_RUN_FAILED,
                    "정산 실행에 실패했습니다. (실행 ID: " + execution.getId() + ", 상태: " + execution.getStatus() + ")");
        }
        return ResponseEntity.ok(new PayoutRunResponse(execution.getId(), execution.getStatus().name()));
    }

    // 정산 1건 수동 실행. 형태만 공개하고 지금은 막아 둠 (막아 둔 이유는 PayoutService.runOrderItem 참고)
    @PostMapping("/run/order-items/{orderItemId}")
    public ResponseEntity<Void> runOrderItem(
            // TODO(#52): 임시 헤더로 회원 정보를 받음. 인증이 붙으면 principal로 교체
            @RequestHeader(MEMBER_ROLE_HEADER) String memberRole,
            @PathVariable Long orderItemId
    ) {
        payoutService.runOrderItem(memberRole, orderItemId);
        return ResponseEntity.noContent().build();
    }

    // 정산 수동 조정. 형태만 공개하고 지금은 막아 둠 (막아 둔 이유는 PayoutService.createAdjustment 참고)
    // 조정 기능을 열면 201과 생성된 정산후보 ID를 응답할 예정
    @PostMapping("/adjustments")
    public ResponseEntity<Void> createAdjustment(
            // TODO(#52): 임시 헤더로 회원 정보를 받음. 인증이 붙으면 principal로 교체
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @RequestHeader(MEMBER_ROLE_HEADER) String memberRole,
            @Valid @RequestBody PayoutAdjustmentCreateRequest request
    ) {
        payoutService.createAdjustment(memberRole, memberId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 정산 목록 조회 (관리자: 전체, 판매자: 본인 정산만)
    @GetMapping
    public ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            // TODO(#52): 임시 헤더로 회원 정보를 받음. 인증이 붙으면 principal로 교체
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @RequestHeader(MEMBER_ROLE_HEADER) String memberRole,
            @Valid @ModelAttribute PayoutSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.search(memberId, memberRole, request.toQuery()).map(PayoutSummaryResponse::from)));
    }

    // 정산 상세 조회 (관리자: 모든 정산, 판매자: 본인 정산만). 판매자 정산이면 그 판매자의 수수료 합계도 함께 보여 줌
    // 항목 목록은 /{payoutId}/items에서 따로 조회
    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutDetailResponse> getDetail(
            // TODO(#52): 임시 헤더로 회원 정보를 받음. 인증이 붙으면 principal로 교체
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @RequestHeader(MEMBER_ROLE_HEADER) String memberRole,
            @PathVariable Long payoutId
    ) {
        return ResponseEntity.ok(PayoutDetailResponse.from(payoutService.getDetail(memberId, memberRole, payoutId)));
    }

    // 정산 항목 조회 (상세와 같은 권한). 항목마다 정산후보 생성 시각과, 조정 건이면 조정 정보를 함께 보여 줌
    @GetMapping("/{payoutId}/items")
    public ResponseEntity<PayoutPageResponse<PayoutItemResponse>> getItems(
            // TODO(#52): 임시 헤더로 회원 정보를 받음. 인증이 붙으면 principal로 교체
            @RequestHeader(MEMBER_ID_HEADER) Long memberId,
            @RequestHeader(MEMBER_ROLE_HEADER) String memberRole,
            @PathVariable Long payoutId,
            @Valid @ModelAttribute PayoutItemSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.getItems(memberId, memberRole, payoutId, request.toQuery()).map(PayoutItemResponse::from)));
    }
}
