package com.ok.member.auth.application.port.out;

import com.ok.member.auth.domain.AccessToken;

import java.util.List;

/**
 * Access Token 발급 출력 포트. 구현은 {@code adapter.out.token}에 있다.
 * 서명 방식(JWT, 키, 헤더)은 구현이 맡아, 서명 기술을 바꿔도 서비스가 바뀌지 않게 한다.
 */
public interface AccessTokenIssuer {

    /**
     * Access Token을 발급한다.
     * 어떤 권한을 담을지는 호출하는 쪽(로그인·재발급)이 회원 정보로 계산해 넘긴다. 구현은 받은 권한을 담기만 한다.
     *
     * @param roles 토큰 권한 목록 ({@code TokenRoles}의 값). 비어 있으면 발급하지 않는다
     * @return 토큰 문자열과 만료 시각
     */
    AccessToken issue(Long memberId, List<String> roles);

}
