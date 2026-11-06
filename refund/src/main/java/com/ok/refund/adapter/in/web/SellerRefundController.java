package com.ok.refund.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.refund.adapter.in.web.dto.RefundDecisionRequest;
import com.ok.refund.adapter.in.web.dto.RefundPageResponse;
import com.ok.refund.adapter.in.web.dto.RefundResponse;
import com.ok.refund.adapter.in.web.dto.SellerRefundPageRequest;
import com.ok.refund.application.service.RefundFacade;
import com.ok.refund.application.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 본인 상품의 요청인지는 서비스가 판매자 ID로 거른다
@RestController
@RequestMapping("/api/v1/seller/refunds")
@PreAuthorize("hasRole('SELLER')")
@RequiredArgsConstructor
public class SellerRefundController implements SellerRefundControllerSpec {

    private final RefundFacade refundFacade;
    private final RefundService refundService;

    @Override
    @GetMapping
    public ResponseEntity<RefundPageResponse> getMyRefunds(
            @CurrentMemberId Long sellerId,
            @Valid @ModelAttribute SellerRefundPageRequest request
    ) {
        return ResponseEntity.ok(RefundPageResponse.from(
                refundService.getSellerRefunds(sellerId, request.status(), request.page())));
    }

    // 승인과 거절은 둘 다 "판매자가 요청 상태를 정한다"는 같은 동작이라 한 API로 받는다
    @Override
    @PatchMapping("/{refundId}")
    public ResponseEntity<RefundResponse> decide(
            @CurrentMemberId Long sellerId,
            @PathVariable Long refundId,
            @Valid @RequestBody RefundDecisionRequest request
    ) {
        return ResponseEntity.ok(RefundResponse.from(refundFacade.decide(request.toCommand(refundId, sellerId))));
    }
}
