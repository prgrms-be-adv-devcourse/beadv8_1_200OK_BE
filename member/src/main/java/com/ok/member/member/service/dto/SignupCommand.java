package com.ok.member.member.service.dto;

public record SignupCommand(
        String loginId,
        String password,
        String email,
        String name,
        String nickname
) {

    /**
     * 로그에 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "SignupCommand[loginId=%s, password=****, email=%s, name=%s, nickname=%s]"
                        .formatted(loginId, email, name, nickname);
    }

}
