package com.ok.member.seller.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.member.seller.adapter.in.web.dto.SellerApplicationHistoryResponse;
import com.ok.member.seller.adapter.in.web.dto.SellerApplicationResponse;
import com.ok.member.seller.adapter.in.web.dto.SellerApplyRequest;
import com.ok.member.seller.adapter.in.web.dto.SellerApplyResponse;
import com.ok.member.seller.application.port.in.SellerApplicationUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 신청은 일반 회원만 하는 일이다. 관리자는 운영 전용 계정이라 판매하지 않는다(MemberType.ADMIN).
// 일반 회원 토큰에만 BUYER 권한이 있어, 신청과 조회 모두 이 권한으로 거른다. 관리자의 신청 조회는 관리자 API가 맡는다
@PreAuthorize("hasRole('BUYER')")
@RestController
@RequestMapping("/api/v1/sellers/me/applications")
@RequiredArgsConstructor
public class SellerApplicationController implements SellerApplicationControllerSpec {

    private final SellerApplicationUseCase sellerApplicationUseCase;

    @Override
    @PostMapping
    public ResponseEntity<SellerApplyResponse> apply(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody SellerApplyRequest request
    ) {
        Long applicationId = sellerApplicationUseCase.apply(memberId, request.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SellerApplyResponse(applicationId));
    }

    @Override
    @GetMapping
    public ResponseEntity<SellerApplicationHistoryResponse> getMyApplications(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(
                SellerApplicationHistoryResponse.from(sellerApplicationUseCase.getMyApplications(memberId)));
    }

    @Override
    @GetMapping("/{applicationId}")
    public ResponseEntity<SellerApplicationResponse> getMyApplication(
            @CurrentMemberId Long memberId,
            @PathVariable Long applicationId
    ) {
        return ResponseEntity.ok(
                SellerApplicationResponse.from(sellerApplicationUseCase.getMyApplication(memberId, applicationId)));
    }

}
