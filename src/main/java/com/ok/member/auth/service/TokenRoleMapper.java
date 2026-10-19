package com.ok.member.auth.service;

import com.ok.common.security.TokenRoles;
import com.ok.member.member.domain.MemberType;

import java.util.List;

/**
 * 회원 정보를 Access Token의 권한 목록({@code roles})으로 바꾼다.
 *
 * <p>회원에 저장된 값과 토큰 권한을 분리해, "회원이 무엇인가"와 "회원이 무엇을 할 수 있는가"를 따로 다룬다.
 * 이 계산을 한 곳에 두어, 판매자 승인 상태처럼 계산에 쓰는 정보가 늘어도 이 클래스만 고치면 된다.
 * 토큰 발급기({@code AccessTokenProvider})는 계산된 목록을 담기만 하고 회원 도메인을 모른다.
 *
 * <p>일반 회원은 구매자 권한을 갖는다. 판매자 권한은 회원 유형이 아니라 판매자 신청·승인 상태로 정해지므로,
 * 판매자 신청 기능이 생기면 승인 상태를 입력으로 받아 {@code SELLER}를 함께 준다.
 *
 * <p>관리자는 운영 전용 계정이라 구매자·판매자 권한을 주지 않는다. 운영 계정으로 실수로 주문하거나 상품을 올리지 않도록
 * 권한을 분리하고, 관리자 기능은 {@code hasRole('ADMIN')} API로 따로 둔다.
 */
final class TokenRoleMapper {

    private TokenRoleMapper() {
    }

    static List<String> toTokenRoles(MemberType type, boolean approvedSeller) {
        if (type == null) {
            throw new IllegalArgumentException("type은 필수입니다.");
        }
        return switch (type) {
            case USER -> approvedSeller ? List.of(TokenRoles.BUYER, TokenRoles.SELLER) : List.of(TokenRoles.BUYER);
            // 관리자는 운영 전용 계정이라 구매자·판매자 권한을 함께 주지 않는다
            case ADMIN -> List.of(TokenRoles.ADMIN);
        };
    }

}
