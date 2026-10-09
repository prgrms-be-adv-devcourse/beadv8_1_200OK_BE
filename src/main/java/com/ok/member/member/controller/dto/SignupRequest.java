package com.ok.member.member.controller.dto;

import com.ok.member.member.service.dto.SignupCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.Locale;

@Schema(description = "일반 회원가입 요청")
public record SignupRequest(

        @Schema(description = "로그인 아이디 (영문, 숫자 4~20자). 대문자는 소문자로 변환되어 저장됩니다.",
                example = "tester01")
        @NotBlank(message = "아이디는 필수입니다.")
        @Pattern(regexp = "^[a-z0-9]{4,20}$", message = "아이디는 영문과 숫자로 4~20자여야 합니다.")
        String loginId,

        @Schema(description = "비밀번호 (영문, 숫자, 특수문자 포함 8~20자)", example = "password1!",
                format = "password")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}$",
                message = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해 8~20자여야 합니다.")
        String password,

        @Schema(description = "이메일", example = "tester@example.com")
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(regexp = "^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}$",
                message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 255, message = "이메일은 255자 이하여야 합니다.")
        String email,

        @Schema(description = "이름 (50자 이하, 앞뒤 공백 제거)", example = "홍길동")
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 50, message = "이름은 50자 이하여야 합니다.")
        String name,

        @Schema(description = "닉네임 (2~30자, 앞뒤 공백 제거)", example = "길동이")
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(min = 2, max = 30, message = "닉네임은 2~30자여야 합니다.")
        String nickname,

        @Schema(description = "이용약관 동의 (필수, true여야 함)", example = "true")
        @AssertTrue(message = "이용약관 동의는 필수입니다.")
        boolean termsAgreed,

        @Schema(description = "개인정보 수집·이용 동의 (필수, true여야 함)", example = "true")
        @AssertTrue(message = "개인정보 수집·이용 동의는 필수입니다.")
        boolean privacyAgreed
) {

    public SignupCommand toCommand() {
        return new SignupCommand(
                loginId,
                password,
                email,
                name,
                nickname
        );
    }

    /**
     * 로그에 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "SignupRequest[loginId=%s, password=****, email=%s, name=%s, nickname=%s, " +
                "termsAgreed=%s, privacyAgreed=%s]"
                .formatted(loginId, email, name, nickname, termsAgreed, privacyAgreed);
    }

    private static String trim(String value) {
        return value == null ? null : value.strip();
    }

    private static String toLowerCaseTrimmed(String value) {
        return value == null ? null : value.strip().toLowerCase();
    }

}
