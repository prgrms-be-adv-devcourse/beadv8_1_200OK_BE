package com.ok.member.wish.application.dto;

/**
 * 내 찜 목록 조회 조건. 값은 요청 DTO에서 검증을 마친 상태다.
 *
 * @param page 0부터 시작하는 페이지 번호
 */
public record WishSearchQuery(
        int page,
        int size
) {
}
