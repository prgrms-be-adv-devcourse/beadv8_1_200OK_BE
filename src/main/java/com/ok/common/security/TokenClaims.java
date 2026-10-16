package com.ok.common.security;

/**
 * Access Token에 담는 claim 이름을 정의한다.
 * 토큰을 발급하는 쪽(auth)과 해석하는 쪽(보안 설정)이 서로의 코드를 참조하지 않고 같은 이름을 쓰도록 common에 둔다.
 */
public final class TokenClaims {

    /** 회원 권한 (BUYER, SELLER, ADMIN). 인증 시 {@code ROLE_} 접두사를 붙여 권한(authority)으로 바꾼다. */
    public static final String ROLE = "role";

    private TokenClaims() {
    }

}
