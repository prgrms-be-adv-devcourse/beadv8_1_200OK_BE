package com.ok.member;

import com.ok.member.member.domain.MemberType;

/**
 * @deprecated 회원 유형과 권한을 분리하면서 {@link MemberType}으로 대체한다.
 *             권한은 토큰 발급 시 계산하므로 이 enum을 직접 쓰지 않는다.
 */
@Deprecated(since = "2026-10-18", forRemoval = true)
public enum MemberRole {
    BUYER,
    SELLER,
    ADMIN
}
