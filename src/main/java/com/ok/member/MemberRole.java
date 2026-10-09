package com.ok.member;

/**
 * 회원 권한. 다른 모듈도 이벤트·인가에서 참조하므로 member 모듈의 공개 API(루트 패키지)에 둔다.
 */
public enum MemberRole {
    BUYER,
    SELLER,
    ADMIN
}
