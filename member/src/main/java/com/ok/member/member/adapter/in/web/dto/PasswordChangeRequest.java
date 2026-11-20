package com.ok.member.member.adapter.in.web.dto;

import com.ok.member.member.application.dto.PasswordChangeCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청. 두 값 모두 공백이 비밀번호의 일부일 수 있어 정규화하지 않는다.
 */
@Schema(description = "비밀번호 변경 요청")
public record PasswordChangeRequest(

        // 현재 비밀번호는 형식(영문·숫자·특수문자)을 검사하지 않는다. 규칙이 생기기 전에 만든 계정(최초 관리자 등)도 비밀번호를 바꿀 수 있어야 한다.
        // 길이 상한은 로그인(LoginRequest)과 같게 둔다. 없으면 아주 긴 값이 bcrypt까지 가 비용만 들거나 72바이트 제한에 걸린다
        @Schema(description = "지금 쓰는 비밀번호 (20자 이하)", format = "password")
        @NotBlank(message = "현재 비밀번호는 필수입니다.")
        @Size(max = 20, message = "비밀번호는 20자 이하여야 합니다.")
        String currentPassword,

        @Schema(description = "새 비밀번호 (영문, 숫자, 특수문자 포함 8~20자)", example = "newPassword1!",
                format = "password")
        @NotBlank(message = "새 비밀번호는 필수입니다.")
        @Pattern(regexp = PasswordRule.REGEX, message = PasswordRule.MESSAGE)
        String newPassword
) {

    public PasswordChangeCommand toCommand() {
        return new PasswordChangeCommand(currentPassword, newPassword);
    }

    /**
     * 비밀번호가 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=****, newPassword=****]";
    }

}
