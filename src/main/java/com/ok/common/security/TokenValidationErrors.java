package com.ok.common.security;

/**
 * 토큰 검증 실패의 원인을 구분하는 오류 코드를 정의한다.
 * 토큰 검증(auth)과 오류 응답(공통 예외 처리)이 서로의 코드를 참조하지 않고 같은 값을 쓰도록 common에 둔다.
 */
public final class TokenValidationErrors {

    /**
     * 토큰이 만료됨. 클라이언트는 재로그인 대신 토큰 재발급을 시도하면 된다.
     * 만료 외의 실패(위조, 형식 오류)와 구분하기 위해 검증 단계에서 이 코드를 남긴다.
     */
    public static final String EXPIRED = "token_expired";

    private TokenValidationErrors() {
    }

}
