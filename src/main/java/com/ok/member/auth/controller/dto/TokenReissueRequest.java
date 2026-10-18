package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.RefreshTokenService;
import com.ok.member.auth.service.dto.TokenReissueCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TokenReissueRequest(

        // 발급하는 Refresh Token보다 긴 값은 해시 계산 전에 막는다. 길이는 발급 규칙(RefreshTokenService)과 같은 상수를 쓴다
        @Schema(description = "로그인·재발급 응답으로 받은 Refresh Token")
        @NotBlank(message = "Refresh Token은 필수입니다.")
        @Size(max = RefreshTokenService.TOKEN_LENGTH, message = "Refresh Token 형식이 올바르지 않습니다.")
        String refreshToken
) {

    public TokenReissueCommand toCommand() {
        return new TokenReissueCommand(refreshToken);
    }

    /**
     * 토큰은 그 자체로 인증 수단이라 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "TokenReissueRequest[refreshToken=****]";
    }

}
