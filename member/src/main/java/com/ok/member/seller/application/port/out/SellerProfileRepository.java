package com.ok.member.seller.application.port.out;

import com.ok.member.seller.domain.SellerProfile;

import java.util.Optional;

/**
 * 판매자 프로필 저장소 출력 포트. 구현은 {@code adapter.out.persistence}에 있다.
 */
public interface SellerProfileRepository {

    Optional<SellerProfile> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    <S extends SellerProfile> S saveAndFlush(S profile);

}
