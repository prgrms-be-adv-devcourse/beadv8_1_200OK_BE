package com.ok.common.security;

/**
 * Access Token에 담는 claim 이름을 정의한다.
 * 토큰을 발급하는 쪽(auth)과 해석하는 쪽(보안 설정)이 서로의 코드를 참조하지 않고 같은 이름을 쓰도록 common에 둔다.
 */
public final class TokenClaims {

    /**
     * 회원 권한 목록 (예: ["BUYER", "SELLER"]). 인증 시 각 값에 {@code ROLE_} 접두사를 붙여 권한(authority)으로 바꾼다.
     * 한 회원이 여러 권한을 가질 수 있어 배열로 담는다. (RFC 9068의 권한 클레임 이름과 같음)
     * 값은 {@link TokenRoles}에 정의한다.
     */
    public static final String ROLES = "roles";

    /**
     * 토큰을 발급할 때의 회원 토큰 버전(숫자). 회원이 비밀번호를 바꾸면 버전이 올라, 이 값이 {@link TokenVersionStore}의 최소 버전보다
     * 낮은 토큰은 만료 전이라도 거부된다(#561). 이 클레임이 없는 토큰(배포 전에 발급됨)은 버전 0으로 본다.
     */
    public static final String TOKEN_VERSION = "ver";

    private TokenClaims() {
    }

}
