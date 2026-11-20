package com.ok.member.seller.adapter.in.web;

import com.ok.member.seller.adapter.in.web.dto.StoreProfileResponse;
import com.ok.member.seller.application.port.in.SellerProfileUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 상품 상세가 로그인 없이 열리는 화면이라 그 아래 보이는 상점 프로필도 공개 API다(SecurityConfig.PUBLIC_ENDPOINTS).
// SellerProfileController는 클래스 전체가 SELLER 권한이라 따로 둔다
@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
public class StoreProfileController implements StoreProfileControllerSpec {

    private final SellerProfileUseCase sellerProfileUseCase;

    @Override
    @GetMapping("/{sellerId}")
    public ResponseEntity<StoreProfileResponse> getStoreProfile(@PathVariable Long sellerId) {
        return ResponseEntity.ok(StoreProfileResponse.from(sellerId, sellerProfileUseCase.getStoreProfile(sellerId)));
    }

}
