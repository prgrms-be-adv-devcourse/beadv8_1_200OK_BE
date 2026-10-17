package com.ok.member;

/**
 * 회원 권한.
 * @deprecated 회원은 {@code MemberType}(USER, ADMIN)을 갖고, 권한(BUYER, SELLER, ADMIN)은 토큰을 발급할 때 계산한다.
 * member 모듈은 더 이상 쓰지 않으며, 상품 API의 임시 헤더(X-Member-Role)만 참조한다.
 */
@Deprecated(forRemoval = true)
public enum MemberRole {
    BUYER,
    SELLER,
    ADMIN
}
