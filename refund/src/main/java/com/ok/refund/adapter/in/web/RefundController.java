package com.ok.refund.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.refund.adapter.in.web.dto.RefundCreateRequest;
import com.ok.refund.adapter.in.web.dto.RefundPageRequest;
import com.ok.refund.adapter.in.web.dto.RefundPageResponse;
import com.ok.refund.adapter.in.web.dto.RefundResponse;
import com.ok.refund.application.service.RefundFacade;
import com.ok.refund.application.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

// 주문은 구매 권한(BUYER)이 있는 회원만 하므로 환불 요청도 같은 권한으로 거른다(판매자 토큰에도 BUYER가 함께 담긴다).
// 본인 주문인지는 서비스가 구매자 ID로 확인한다
@RestController
@RequestMapping("/api/v1/refunds")
@PreAuthorize("hasRole('BUYER')")
@RequiredArgsConstructor
public class RefundController implements RefundControllerSpec {

    private final RefundFacade refundFacade;
    private final RefundService refundService;

    @Override
    @PostMapping
    public ResponseEntity<RefundResponse> request(
            @CurrentMemberId Long buyerId,
            @Valid @RequestBody RefundCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RefundResponse.from(refundFacade.request(request.toCommand(buyerId))));
    }

    @Override
    @GetMapping
    public ResponseEntity<RefundPageResponse> getMyRefunds(
            @CurrentMemberId Long buyerId,
            @Valid @ModelAttribute RefundPageRequest request
    ) {
        return ResponseEntity.ok(RefundPageResponse.from(refundService.getBuyerRefunds(buyerId, request.page())));
    }

    // 새로 만드는 게 아니라 있는 요청의 상태를 바꾸므로 201이 아니라 200이다
    @Override
    @PostMapping("/{refundId}/withdraw")
    public ResponseEntity<RefundResponse> withdraw(
            @CurrentMemberId Long buyerId,
            @PathVariable Long refundId
    ) {
        return ResponseEntity.ok(RefundResponse.from(refundService.withdraw(refundId, buyerId)));
    }
}
