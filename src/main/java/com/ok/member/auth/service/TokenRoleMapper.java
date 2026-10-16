package com.ok.member.auth.service;

import com.ok.common.security.TokenRoles;
import com.ok.member.MemberRole;

import java.util.List;

/**
 * 회원 정보를 Access Token의 권한 목록({@code roles})으로 바꾼다.
 *
 * <p>회원에 저장된 값과 토큰 권한을 분리해, "회원이 무엇인가"와 "회원이 무엇을 할 수 있는가"를 따로 다룬다.
 * 이 계산을 한 곳에 두어, 회원 구조가 바뀌어도(예: 회원 유형과 판매자 승인 상태) 이 클래스만 고치면 된다.
 * 토큰 발급기({@code AccessTokenProvider})는 계산된 목록을 담기만 하고 회원 도메인을 모른다.
 */
final class TokenRoleMapper {

    private TokenRoleMapper() {
    }

    static List<String> toTokenRoles(MemberRole role) {
        if (role == null) {
            throw new IllegalArgumentException("role은 필수입니다.");
        }
        return switch (role) {
            case BUYER -> List.of(TokenRoles.BUYER);
            // 판매자도 다른 판매자의 상품을 구매할 수 있으므로 구매자 권한을 함께 준다
            case SELLER -> List.of(TokenRoles.BUYER, TokenRoles.SELLER);
            case ADMIN -> List.of(TokenRoles.ADMIN);
        };
    }

}
