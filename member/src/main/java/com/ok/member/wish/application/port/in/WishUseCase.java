package com.ok.member.wish.application.port.in;

import com.ok.member.wish.application.dto.WishResult;
import com.ok.member.wish.application.dto.WishSearchQuery;
import org.springframework.data.domain.Page;

/**
 * 관심상품(찜) 등록·해제·조회 유스케이스.
 * 구매 권한(BUYER)은 컨트롤러가 토큰으로 확인한다. 상품이 있는지는 확인하지 않는다(#817).
 */
public interface WishUseCase {

    /**
     * 상품을 찜한다. 이미 찜했으면 아무것도 바꾸지 않고, 해제했던 찜이면 지금 시각으로 되살린다.
     * 같은 상품을 동시에 찜해도 실패하지 않고 한 줄만 남는다.
     */
    void wish(Long memberId, Long productId);

    /**
     * 찜을 해제한다. 찜하지 않았거나 이미 해제한 상품이어도 실패하지 않는다.
     */
    void unwish(Long memberId, Long productId);

    /**
     * 해제하지 않은 내 찜을 최근에 찜한 순서로 반환한다.
     */
    Page<WishResult> getMyWishes(Long memberId, WishSearchQuery query);

}
