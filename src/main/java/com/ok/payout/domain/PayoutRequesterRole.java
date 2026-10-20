package com.ok.payout.domain;

import com.ok.common.security.TokenRoles;

import java.util.Collection;

/**
 * 정산 API를 요청한 사람의 역할을 정산 모듈 기준으로 해석한다.
 * member 모듈의 역할 타입에 의존하지 않도록, 토큰의 역할 목록을 이 enum으로 바꿔 쓴다.
 */
public enum PayoutRequesterRole {

    ADMIN,
    SELLER,
    // 정산 권한이 없는 나머지 역할 (구매자, 알 수 없는 값)
    OTHER;

    /**
     * 토큰의 권한 목록({@link TokenRoles} 값)을 정산 기준 역할 하나로 바꾼다.
     * 토큰에는 역할이 여러 개 들어 있을 수 있어(예: 판매자 {@code ["BUYER", "SELLER"]}) 권한이 큰 순서(ADMIN → SELLER)로 고른다.
     * null이나 빈 목록, 모르는 값만 있으면 {@code OTHER}로 본다 (잘못된 값이 권한을 얻지 않도록 거부 쪽으로 해석).
     */
    public static PayoutRequesterRole fromTokenRoles(Collection<String> roles) {
        if (roles == null) {
            return OTHER;
        }
        if (roles.contains(TokenRoles.ADMIN)) {
            return ADMIN;
        }
        if (roles.contains(TokenRoles.SELLER)) {
            return SELLER;
        }
        return OTHER;
    }
}
