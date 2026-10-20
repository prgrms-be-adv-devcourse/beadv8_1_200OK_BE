package com.ok.member.member.controller.dto;

import com.ok.member.member.service.dto.MemberInfoResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "내 정보 응답")
public record MemberInfoResponse(

        @Schema(description = "로그인 아이디", example = "seoyeon88")
        String loginId,

        @Schema(description = "이메일", example = "seoyeon88@example.com")
        String email,

        @Schema(description = "이름")
        String name,

        @Schema(description = "닉네임. 리뷰와 판매자 화면에 보인다")
        String nickname
) {

    public static MemberInfoResponse from(MemberInfoResult result) {
        return new MemberInfoResponse(
                result.loginId(),
                result.email(),
                result.name(),
                result.nickname()
        );
    }

}
