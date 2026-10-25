package com.ok.member.member.service.dto;

/**
 * 내 정보 조회·수정 결과.
 */
public record MemberInfoResult(
        String loginId,
        String email,
        String name,
        String nickname
) {
}
