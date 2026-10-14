package com.ok.member.auth.controller.dto;

import com.ok.member.auth.service.dto.LoginCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

@Schema(description = "로그인 요청")
public record LoginRequest(

        @Schema(description = "로그인 아이디. 대소문자를 구분하지 않습니다.", example = "tester01")
        @NotBlank(message = "아이디는 필수입니다.")
        String loginId,

        // bcrypt는 72바이트를 넘는 비밀번호를 비교하지 못하고 예외를 던진다(500).
        // 가입 규칙(최대 20자)과 같게 막으면 한글 등 멀티바이트 문자로도 72바이트를 넘지 않는다.
        @Schema(description = "비밀번호", example = "password1!", format = "password")
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(max = 20, message = "비밀번호는 20자 이하여야 합니다.")
        String password
) {

    /**
     * 가입 시와 같은 규칙으로 아이디를 정규화한다. 검증은 정규화된 값을 기준으로 수행된다.
     * 비밀번호는 공백도 값의 일부일 수 있어 정규화하지 않는다.
     *
     * <p>member의 {@code SignupRequest}와 같은 규칙이어야 가입한 아이디로 로그인할 수 있다.
     * auth를 별도 모듈로 분리할 예정이라 member의 코드를 참조하지 않고 규칙을 따로 둔다. 한쪽을 바꾸면 다른 쪽도 함께 바꾼다.
     */
    public LoginRequest {
        // Locale.ROOT를 지정하지 않으면 서버 기본 로케일에 따라 결과가 달라진다(예: 터키어에서 "I" → "ı")
        loginId = loginId == null ? null : loginId.strip().toLowerCase(Locale.ROOT);
    }

    public LoginCommand toCommand() {
        return new LoginCommand(loginId, password);
    }

    /**
     * 로그에 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "LoginRequest[loginId=%s, password=****]".formatted(loginId);
    }

}
