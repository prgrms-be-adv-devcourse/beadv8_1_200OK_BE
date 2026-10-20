package com.ok.member.seller.controller;

import com.ok.common.security.CurrentMemberId;
import com.ok.member.seller.controller.dto.SellerProfileRequest;
import com.ok.member.seller.controller.dto.SellerProfileResponse;
import com.ok.member.seller.service.SellerProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 판매자 상품 API와 같이 토큰의 SELLER 권한으로 판단한다. 판매자 승인을 받으면 로그인·재발급 때 SELLER가 붙는다.
@PreAuthorize("hasRole('SELLER')")
@RestController
@RequestMapping("/api/v1/sellers/me/profile")
@RequiredArgsConstructor
public class SellerProfileController implements SellerProfileControllerSpec {

    private final SellerProfileService sellerProfileService;

    @Override
    @GetMapping
    public ResponseEntity<SellerProfileResponse> getMyProfile(@CurrentMemberId Long memberId) {
        return ResponseEntity.ok(SellerProfileResponse.from(sellerProfileService.getMyProfile(memberId)));
    }

    @Override
    @PostMapping
    public ResponseEntity<SellerProfileResponse> register(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody SellerProfileRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SellerProfileResponse.from(sellerProfileService.register(memberId, request.toCommand())));
    }

    @Override
    @PutMapping
    public ResponseEntity<SellerProfileResponse> update(
            @CurrentMemberId Long memberId,
            @Valid @RequestBody SellerProfileRequest request
    ) {
        return ResponseEntity.ok(SellerProfileResponse.from(sellerProfileService.update(memberId, request.toCommand())));
    }

}
