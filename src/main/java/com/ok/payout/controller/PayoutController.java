package com.ok.payout.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.common.security.TokenClaims;
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
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 개발 중 수동 실행용 임시 API. prod에서는 등록하지 않음 (prod 정산은 스케줄러로만 실행)
// 토큰 인증은 붙었지만 SecurityConfig가 아직 전환 기간(permitAll)이라 prod에는 열지 않음 (기본 거부 전환은 #108)
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/payouts")
@RequiredArgsConstructor
public class PayoutController implements PayoutControllerSpec {

    // TODO(#52): 정산 1건 실행 API만 쓰는 임시 헤더. 이 API는 기능 없이 형태만 둔 상태(항상 501)라
    // 토큰 전환은 기능을 열 때(재처리 정책 확정 후) 함께 함
    static final String MEMBER_ROLE_HEADER = "X-Member-Role";

    private final PayoutJobRunner payoutJobRunner;
    private final PayoutService payoutService;

    // 정산 전체 수동 실행 (개발 중 수동 실행용. 실패 응답은 #67에서 처리)
    // 관리자만 쓰는 운영 기능이라 컨트롤러에서 역할로 막음 (DEVELOPMENT.md "역할 제한": 특정 역할만 쓰는 API는 @PreAuthorize)
    // 조회 API와 달리 역할마다 동작이 다르지 않아 서비스로 역할을 넘길 필요가 없음
    // 토큰 없음은 401, 관리자가 아니면 403 (ApiControllerAdvice.handleAccessDeniedException)
    @PreAuthorize("hasRole('ADMIN')")
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
    @Override
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
    // 관리자 전용이라 /run과 같이 @PreAuthorize로 막음. 토큰 없음은 401, 관리자가 아니면 403
    @PreAuthorize("hasRole('ADMIN')")
    @Override
    @PostMapping("/adjustments")
    public ResponseEntity<Void> createAdjustment(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody PayoutAdjustmentCreateRequest request
    ) {
        payoutService.createAdjustment(memberId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // 정산 목록 조회 (관리자: 전체, 판매자: 본인 정산만)
    // 역할에 따라 동작이 달라 @PreAuthorize로 막지 않고, 토큰의 역할 목록을 서비스에 넘겨 서비스가 판단함 (#113: 정책 검증은 서비스)
    @Override
    @GetMapping
    public ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            @CurrentMemberId Long memberId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @ModelAttribute PayoutSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.search(memberId, tokenRoles(jwt), request.toQuery()).map(PayoutSummaryResponse::from)));
    }

    // 정산 상세 조회 (관리자: 모든 정산, 판매자: 본인 정산만). 판매자 정산이면 그 판매자의 수수료 합계도 함께 보여 줌
    // 항목 목록은 /{payoutId}/items에서 따로 조회
    @Override
    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutDetailResponse> getDetail(
            @CurrentMemberId Long memberId,
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long payoutId
    ) {
        return ResponseEntity.ok(PayoutDetailResponse.from(payoutService.getDetail(memberId, tokenRoles(jwt), payoutId)));
    }

    // 정산 항목 조회 (상세와 같은 권한). 항목마다 정산후보 생성 시각과, 조정 건이면 조정 정보를 함께 보여 줌
    @Override
    @GetMapping("/{payoutId}/items")
    public ResponseEntity<PayoutPageResponse<PayoutItemResponse>> getItems(
            @CurrentMemberId Long memberId,
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long payoutId,
            @Valid @ModelAttribute PayoutItemSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.getItems(memberId, tokenRoles(jwt), payoutId, request.toQuery()).map(PayoutItemResponse::from)));
    }

    // 토큰의 권한 목록(roles 클레임)을 꺼냄. 값은 TokenRoles 이름 그대로(예: ["BUYER", "SELLER"])
    // Spring 권한(ROLE_ADMIN)이 아니라 클레임을 읽는 이유: ROLE_ 접두사를 정산에서 다시 떼지 않으려고
    // 컨트롤러는 domain 타입을 쓸 수 없어(api.md) 문자열 목록으로만 넘기고, PayoutRequesterRole 변환은 서비스가 함
    // 지금 쓰는 곳은 모두 @CurrentMemberId가 함께 있어 토큰이 없으면 그쪽에서 먼저 401이 남
    // 그래도 다른 파라미터에 기대지 않도록 여기서도 직접 막음 (@CurrentMemberId 없이 쓰는 메서드가 생겨도 NPE → 500이 되지 않게)
    // 거부를 OTHER(403)로 돌리지 않고 401로 던지는 이유: 로그인을 안 한 것이지 권한이 없는 게 아님
    // RestApiException(CommonErrorCode.UNAUTHORIZED)이 아니라 인증 예외를 던지는 이유: 공통 처리기가 인증 예외를 받아야
    //   CommonErrorCode.UNAUTHORIZED 응답에 WWW-Authenticate 헤더까지 붙여 다른 401과 같은 모양이 됨 (CurrentMemberIdArgumentResolver와 같은 방식)
    private List<String> tokenRoles(Jwt jwt) {
        if (jwt == null) {
            throw new AuthenticationCredentialsNotFoundException("로그인이 필요합니다.");
        }
        return jwt.getClaimAsStringList(TokenClaims.ROLES);
    }
}
