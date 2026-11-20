package com.ok.member.wish.application.port.out;

import com.ok.member.wish.domain.MemberProductWish;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * 관심상품 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface MemberProductWishRepository {

    /**
     * 회원·상품 쌍의 행을 찾는다. 해제한 행도 찾는다.
     * 다시 찜할 때 새로 만들지 않고 되살리기 위해서다.
     */
    Optional<MemberProductWish> findByMemberIdAndProductId(Long memberId, Long productId);

    /**
     * 해제하지 않은 찜을 최근에 찜한 순서로 찾는다.
     * 같은 시각에 찜한 행도 페이지 사이에서 겹치거나 빠지지 않도록 id로 한 번 더 정렬한다.
     */
    Page<MemberProductWish> findAllByMemberIdAndDeletedAtIsNullOrderByWishedAtDescIdDesc(Long memberId, Pageable pageable);

    /**
     * 행을 저장하고 바로 DB에 반영한다.
     * 유니크 위반을 트랜잭션 안에서 바로 드러내, Facade가 잡아 다시 시도할 수 있게 한다.
     */
    <S extends MemberProductWish> S saveAndFlush(S wish);

}
