package com.ok.member.member.service.dto;

/**
 * 최초 관리자 계정 입력. 값은 설정값에서 정규화·검증을 마친 상태다.
 *
 * @param loginId  로그인 아이디 (소문자, 앞뒤 공백 제거)
 * @param password 비밀번호 평문. 서비스가 인코딩해 저장한다
 * @param email    이메일 (소문자, 앞뒤 공백 제거)
 */
public record AdminAccountCommand(
        String loginId,
        String password,
        String email
) {

    /**
     * 로그에 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "AdminAccountCommand[loginId=%s, password=****, email=%s]".formatted(loginId, email);
    }

}
