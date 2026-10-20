package com.ok.member.member.service.dto;

/**
 * 내 정보 수정 입력. 값은 요청 DTO에서 정규화·형식 검증을 마친 상태다.
 */
public record MemberInfoUpdateCommand(
        String name,
        String nickname
) {
}
