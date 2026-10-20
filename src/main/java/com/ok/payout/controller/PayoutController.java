package com.ok.payout.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.payout.controller.dto.PayoutItemSearchRequest;
import com.ok.payout.controller.dto.PayoutPageResponse;
import com.ok.payout.controller.dto.PayoutSellerDetailResponse;
import com.ok.payout.controller.dto.PayoutSellerItemResponse;
import com.ok.payout.controller.dto.PayoutSellerSearchRequest;
import com.ok.payout.controller.dto.PayoutSummaryResponse;
import com.ok.payout.service.PayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 본인 정산 조회 API. 관리자용은 PayoutAdminController (#198에서 분리)
// 토큰 인증은 붙었지만 SecurityConfig가 아직 전환 기간(permitAll)이라 prod에는 열지 않음 (기본 거부 전환은 #108)
// 판매자만 쓰는 API라 메서드마다 @PreAuthorize로 막음 (DEVELOPMENT.md "역할 제한"). 토큰 없음은 401, 판매자가 아니면 403
// 회원 ID는 토큰에서 받음(@CurrentMemberId). 요청에 대상 ID를 받지 않아서 목록은 본인 것만 나오고,
// URL에 정산 ID가 들어가는 상세·항목은 서비스가 본인 정산인지 다시 확인함 (PayoutService.checkOwnPayout)
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/payouts/me")
@RequiredArgsConstructor
public class PayoutController implements PayoutControllerSpec {

    private final PayoutService payoutService;

    // 본인 정산 목록 조회 (판매자 몫만)
    @PreAuthorize("hasRole('SELLER')")
    @Override
    @GetMapping
    public ResponseEntity<PayoutPageResponse<PayoutSummaryResponse>> search(
            @CurrentMemberId Long memberId,
            @Valid @ModelAttribute PayoutSellerSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.searchForSeller(memberId, request.toQuery()).map(PayoutSummaryResponse::from)));
    }

    // 본인 정산 상세 조회. 이 판매자의 수수료 합계도 함께 보여 줌. 항목 목록은 /{payoutId}/items에서 따로 조회
    @PreAuthorize("hasRole('SELLER')")
    @Override
    @GetMapping("/{payoutId}")
    public ResponseEntity<PayoutSellerDetailResponse> getDetail(
            @CurrentMemberId Long memberId,
            @PathVariable Long payoutId
    ) {
        return ResponseEntity.ok(PayoutSellerDetailResponse.from(payoutService.getDetailForSeller(memberId, payoutId)));
    }

    // 본인 정산 항목 조회. 조정 건이면 조정 사유만 보여 줌
    @PreAuthorize("hasRole('SELLER')")
    @Override
    @GetMapping("/{payoutId}/items")
    public ResponseEntity<PayoutPageResponse<PayoutSellerItemResponse>> getItems(
            @CurrentMemberId Long memberId,
            @PathVariable Long payoutId,
            @Valid @ModelAttribute PayoutItemSearchRequest request
    ) {
        return ResponseEntity.ok(PayoutPageResponse.from(
                payoutService.getItemsForSeller(memberId, payoutId, request.toQuery()).map(PayoutSellerItemResponse::from)));
    }
}
