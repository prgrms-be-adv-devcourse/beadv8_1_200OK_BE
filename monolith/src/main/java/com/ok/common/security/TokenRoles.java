package com.ok.common.security;

/**
 * Access Token의 {@code roles} 클레임에 담는 권한 이름을 정의한다.
 *
 * <p>회원 엔티티의 값이 아니라 "이 회원이 무엇을 할 수 있는가"를 나타내는 인가용 이름이다.
 * 토큰을 발급할 때 회원 정보로부터 계산하고, 각 모듈은 {@code @PreAuthorize("hasRole('SELLER')")}처럼 이 이름으로 인가한다.
 */
public final class TokenRoles {

    /** 상품을 구매할 수 있다. 판매자도 구매할 수 있으므로 판매자 토큰에도 함께 담긴다. */
    public static final String BUYER = "BUYER";

    /** 상품을 등록·관리할 수 있다. */
    public static final String SELLER = "SELLER";

    /** 운영 기능을 쓸 수 있다. */
    public static final String ADMIN = "ADMIN";

    private TokenRoles() {
    }

}
