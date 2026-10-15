package com.ok.payout.domain;

/**
 * 정산 API를 요청한 사람의 역할을 정산 모듈 기준으로 해석한다.
 * member 모듈의 역할 타입에 의존하지 않도록, 요청으로 받은 역할 문자열을 이 enum으로 바꿔 쓴다.
 */
public enum PayoutRequesterRole {

    ADMIN,
    SELLER,
    // 정산 권한이 없는 나머지 역할 (구매자, 알 수 없는 값)
    OTHER;

    /**
     * 역할 문자열을 정산 기준 역할로 바꾼다.
     * 모르는 값이나 null은 권한이 없는 {@code OTHER}로 본다 (잘못된 값이 권한을 얻지 않도록 거부 쪽으로 해석).
     */
    public static PayoutRequesterRole from(String role) {
        if (ADMIN.name().equals(role)) {
            return ADMIN;
        }
        if (SELLER.name().equals(role)) {
            return SELLER;
        }
        return OTHER;
    }
}
