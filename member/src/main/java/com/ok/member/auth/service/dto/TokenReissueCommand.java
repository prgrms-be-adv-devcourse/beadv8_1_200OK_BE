package com.ok.member.auth.service.dto;

public record TokenReissueCommand(String refreshToken) {

    /**
     * 토큰은 그 자체로 인증 수단이라 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "TokenReissueCommand[refreshToken=****]";
    }

}
