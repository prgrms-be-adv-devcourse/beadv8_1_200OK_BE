package com.ok.member.seller.controller;

import com.ok.member.seller.controller.dto.SellerApplicationAdminPageResponse;
import com.ok.member.seller.controller.dto.SellerApplicationAdminResponse;
import com.ok.member.seller.controller.dto.SellerApplicationSearchRequest;
import com.ok.member.seller.service.SellerApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
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

}
