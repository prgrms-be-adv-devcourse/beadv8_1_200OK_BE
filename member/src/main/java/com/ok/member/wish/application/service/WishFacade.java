package com.ok.member.wish.application.service;

import com.ok.member.wish.application.dto.WishResult;
import com.ok.member.wish.application.dto.WishSearchQuery;
import com.ok.member.wish.application.port.in.WishUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 관심상품 유스케이스의 진입점. 동시 등록으로 생긴 유니크 위반을 트랜잭션 밖에서 잡아 한 번 다시 시도한다.
 * 위반이 난 트랜잭션은 롤백 전용이 되어 그 안에서는 복구할 수 없으므로 트랜잭션을 갖지 않는다.
 */
@Component
@RequiredArgsConstructor
public class WishFacade implements WishUseCase {

    private final WishService wishService;

    @Override
    public void wish(Long memberId, Long productId) {
        try {
            wishService.wish(memberId, productId);
        } catch (DataIntegrityViolationException e) {
            // 다른 요청이 먼저 행을 만들었으므로 다시 시도하면 "이미 찜함"으로 끝난다
            wishService.wish(memberId, productId);
        }
    }

    @Override
    public void unwish(Long memberId, Long productId) {
        wishService.unwish(memberId, productId);
    }

    @Override
    public Page<WishResult> getMyWishes(Long memberId, WishSearchQuery query) {
        return wishService.getMyWishes(memberId, query);
    }

}
