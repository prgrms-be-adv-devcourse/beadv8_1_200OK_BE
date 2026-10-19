package com.ok.member.seller.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.member.seller.controller.dto.SellerApplicationAdminPageResponse;
import com.ok.member.seller.controller.dto.SellerApplicationAdminResponse;
import com.ok.member.seller.controller.dto.SellerApplicationRejectRequest;
import com.ok.member.seller.controller.dto.SellerApplicationSearchRequest;
import com.ok.member.seller.service.SellerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 신청 심사는 관리자만 한다.
@PreAuthorize("hasRole('ADMIN')")
@RestController
@RequestMapping("/api/v1/admin/seller-applications")
@RequiredArgsConstructor
public class SellerApplicationAdminController implements SellerApplicationAdminControllerSpec {

    private final SellerApplicationService sellerApplicationService;

    @Override
    @GetMapping
    public ResponseEntity<SellerApplicationAdminPageResponse> getApplications(
            @Valid @ModelAttribute SellerApplicationSearchRequest request
    ) {
        return ResponseEntity.ok(SellerApplicationAdminPageResponse.from(
                sellerApplicationService.getApplications(request.toQuery())));
    }

    @Override
    @GetMapping("/{applicationId}")
    public ResponseEntity<SellerApplicationAdminResponse> getApplication(@PathVariable Long applicationId) {
        return ResponseEntity.ok(SellerApplicationAdminResponse.from(
                sellerApplicationService.getApplication(applicationId)));
    }

    // 처리 API는 처리 후의 상세를 돌려준다. 관리자 화면이 다시 조회하지 않고 바뀐 상태를 바로 보여 줄 수 있다
    @Override
    @PostMapping("/{applicationId}/review")
    public ResponseEntity<SellerApplicationAdminResponse> startReview(@PathVariable Long applicationId) {
        return ResponseEntity.ok(SellerApplicationAdminResponse.from(
                sellerApplicationService.startReview(applicationId)));
    }

    @Override
    @PostMapping("/{applicationId}/approve")
    public ResponseEntity<SellerApplicationAdminResponse> approve(
            @CurrentMemberId Long adminId,
            @PathVariable Long applicationId
    ) {
        return ResponseEntity.ok(SellerApplicationAdminResponse.from(
                sellerApplicationService.approve(adminId, applicationId)));
    }

    @Override
    @PostMapping("/{applicationId}/reject")
    public ResponseEntity<SellerApplicationAdminResponse> reject(
            @CurrentMemberId Long adminId,
            @PathVariable Long applicationId,
            @Valid @RequestBody SellerApplicationRejectRequest request
    ) {
        return ResponseEntity.ok(SellerApplicationAdminResponse.from(
                sellerApplicationService.reject(adminId, applicationId, request.toCommand())));
    }

}
