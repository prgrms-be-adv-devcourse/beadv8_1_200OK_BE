package com.ok.member.member.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "일반 회원가입 응답")
public record SignupResponse(

        @Schema(description = "생성된 회원 id", example = "1")
        Long memberId
) {
}
