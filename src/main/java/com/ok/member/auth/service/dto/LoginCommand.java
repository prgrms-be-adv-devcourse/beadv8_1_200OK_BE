package com.ok.member.auth.service.dto;

/**
 * 로그인 입력값을 담는다.
 *
 * @param loginId  정규화(소문자, 앞뒤 공백 제거)된 로그인 아이디
 * @param password 평문 비밀번호
 */
public record LoginCommand(String loginId, String password) {

    /**
     * 로그에 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "LoginCommand[loginId=%s, password=****]".formatted(loginId);
    }

}
