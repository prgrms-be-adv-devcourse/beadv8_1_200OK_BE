package com.ok.member.member.controller.dto;

import com.ok.member.member.service.dto.MemberInfoUpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "내 정보 수정 요청. 이름과 닉네임을 모두 보내며, 아이디와 이메일은 바꿀 수 없다")
public record MemberInfoUpdateRequest(

        @Schema(description = "이름 (50자 이하, 앞뒤 공백 제거)", example = "김철수")
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name,

        @Schema(description = "닉네임 (2~30자, 앞뒤 공백 제거). 다른 회원과 같아도 된다", example = "풀떼기먹자")
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 30, message = "닉네임은 2~30자여야 합니다.")
        String nickname
) {

    /**
     * 요청을 역직렬화하는 시점에 앞뒤 공백을 제거한다. 검증은 정규화된 값을 기준으로 수행된다.
     * 회원가입(SignupRequest)과 같은 규칙이어야 가입할 때와 수정할 때 같은 값이 같게 저장된다.
     */
    public MemberInfoUpdateRequest {
        name = trim(name);
        nickname = trim(nickname);
    }

    public MemberInfoUpdateCommand toCommand() {
        return new MemberInfoUpdateCommand(name, nickname);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

}
