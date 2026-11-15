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

    // 낙관적 락(@Version) 충돌을 커밋 전에 드러내 에러 코드로 바꾸고, 충돌이 없을 때만 출고지 메시지를 남기려고 쓴다
    void flush();

}
