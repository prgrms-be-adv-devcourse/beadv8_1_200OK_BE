package com.ok.refund.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.refund.controller.dto.RefundDecisionRequest;
import com.ok.refund.controller.dto.RefundPageResponse;
import com.ok.refund.controller.dto.RefundResponse;
import com.ok.refund.controller.dto.SellerRefundPageRequest;
import com.ok.refund.service.RefundFacade;
import com.ok.refund.service.RefundService;
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
