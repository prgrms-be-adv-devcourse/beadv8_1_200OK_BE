package com.ok.member.seller.application.port.in;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.application.dto.SellerProfileCommand;
import com.ok.member.seller.application.dto.SellerProfileResult;

/**
 * 판매자의 상점 프로필 조회·등록·수정 유스케이스.
 * 판매자 여부는 컨트롤러가 토큰의 SELLER 권한으로 확인한다.
 */
public interface SellerProfileUseCase {

    /**
     * 판매자 본인의 프로필을 반환한다.
     *
     * @throws RestApiException 프로필을 등록하지 않았으면 {@code SELLER_PROFILE_NOT_FOUND}
     */
    SellerProfileResult getMyProfile(Long memberId);

    /**
     * 판매자 프로필을 등록한다. 한 회원은 프로필(상점)을 하나만 가진다.
     *
     * @throws RestApiException 이미 등록했으면 {@code SELLER_PROFILE_ALREADY_EXISTS},
     *                          사진 주소가 판매자 프로필용이 아니면 {@code INVALID_PROFILE_IMAGE_URL}
     */
    SellerProfileResult register(Long memberId, SellerProfileCommand command);

    /**
     * 판매자 프로필 전체를 새 값으로 바꾼다. 사진 URL이 없으면 사진을 지운다.
     *
     * @throws RestApiException 프로필을 등록하지 않았으면 {@code SELLER_PROFILE_NOT_FOUND},
     *                          사진 주소가 판매자 프로필용이 아니면 {@code INVALID_PROFILE_IMAGE_URL}
     */
    SellerProfileResult update(Long memberId, SellerProfileCommand command);

}
