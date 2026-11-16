package com.ok.member.auth.domain;

/**
 * 재발급으로 회전한 Refresh Token. 새 토큰과 함께, 회원 상태를 확인할 수 있게 토큰 주인의 회원 id를 담는다.
 *
 * @param memberId 토큰을 발급받은 회원 id
 * @param issued   새 토큰 원문과 만료 시각. 원문은 이 값으로만 얻을 수 있다
 */
public record RotatedRefreshToken(Long memberId, IssuedRefreshToken issued) {
}
