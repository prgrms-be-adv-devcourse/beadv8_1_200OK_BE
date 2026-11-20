package com.ok.member.wish.application.service;

import com.ok.member.wish.application.dto.WishResult;
import com.ok.member.wish.application.dto.WishSearchQuery;
import com.ok.member.wish.application.port.out.MemberProductWishRepository;
import com.ok.member.wish.domain.MemberProductWish;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 관심상품의 DB 작업을 처리한다. 동시 등록의 유니크 위반을 다시 시도하는 일은 {@link WishFacade}가 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishService {

    private final MemberProductWishRepository wishRepository;
    private final Clock clock;

    /**
     * 상품을 찜한다. 행이 없으면 만들고, 있으면 해제했던 경우에만 되살린다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 같은 상품을 동시에 찜해 다른 요청이 먼저 행을 만들었으면
     */
    @Transactional
    public void wish(Long memberId, Long productId) {
        LocalDateTime now = now();
        wishRepository.findByMemberIdAndProductId(memberId, productId)
                .ifPresentOrElse(
                        wish -> wish.rewish(now),
                        () -> wishRepository.saveAndFlush(MemberProductWish.wish(memberId, productId, now)));
    }

    @Transactional
    public void unwish(Long memberId, Long productId) {
        wishRepository.findByMemberIdAndProductId(memberId, productId)
                .ifPresent(wish -> wish.unwish(now()));
    }

    public Page<WishResult> getMyWishes(Long memberId, WishSearchQuery query) {
        return wishRepository.findAllByMemberIdAndDeletedAtIsNullOrderByWishedAtDescIdDesc(
                        memberId, PageRequest.of(query.page(), query.size()))
                .map(wish -> new WishResult(wish.getProductId(), wish.getWishedAt()));
    }

    // DATETIME(6)에 저장된 값과 메모리의 값이 나노초 차이로 어긋나지 않게 한다
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

}
