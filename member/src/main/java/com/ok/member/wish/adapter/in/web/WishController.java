package com.ok.member.wish.adapter.in.web;

import com.ok.common.security.CurrentMemberId;
import com.ok.member.wish.adapter.in.web.dto.WishPageRequest;
import com.ok.member.wish.adapter.in.web.dto.WishPageResponse;
import com.ok.member.wish.application.port.in.WishUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 찜은 구매하는 회원의 기능이라 토큰의 BUYER 권한으로 판단한다. 판매자도 BUYER를 가지므로 막히는 것은 관리자뿐이다
@PreAuthorize("hasRole('BUYER')")
@RestController
@RequestMapping("/api/v1/members/me/wishes")
@RequiredArgsConstructor
public class WishController implements WishControllerSpec {

    private final WishUseCase wishUseCase;

    // 검증 어노테이션(@Positive, @Valid)은 Spec에만 둔다. 재정의한 메서드가 매개변수 제약을 다르게 선언하면 Bean Validation이 거부한다(HV000151)
    @Override
    @PutMapping("/{productId}")
    public ResponseEntity<Void> wish(@CurrentMemberId Long memberId, @PathVariable Long productId) {
        wishUseCase.wish(memberId, productId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> unwish(@CurrentMemberId Long memberId, @PathVariable Long productId) {
        wishUseCase.unwish(memberId, productId);
        return ResponseEntity.noContent().build();
    }

    @Override
    @GetMapping
    public ResponseEntity<WishPageResponse> getMyWishes(
            @CurrentMemberId Long memberId,
            @ModelAttribute WishPageRequest request
    ) {
        return ResponseEntity.ok(WishPageResponse.from(wishUseCase.getMyWishes(memberId, request.toQuery())));
    }

}
