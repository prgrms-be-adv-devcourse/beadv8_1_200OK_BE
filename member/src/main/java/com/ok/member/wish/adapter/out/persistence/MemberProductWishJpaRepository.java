package com.ok.member.wish.adapter.out.persistence;

import com.ok.member.wish.application.port.out.MemberProductWishRepository;
import com.ok.member.wish.domain.MemberProductWish;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link MemberProductWishRepository}의 Spring Data JPA 구현. 쿼리 메서드는 포트에 선언한 이름으로 만들어진다.
 */
public interface MemberProductWishJpaRepository
        extends JpaRepository<MemberProductWish, Long>, MemberProductWishRepository {

    /**
     * JpaRepository와 포트가 함께 물려주는 메서드를 하나로 합친다.
     * 다시 선언하지 않으면 이 타입으로 부를 때 모호한 호출이 된다. 구현은 그대로 Spring Data 기본 구현이다.
     */
    @Override
    <S extends MemberProductWish> S saveAndFlush(S wish);

}
