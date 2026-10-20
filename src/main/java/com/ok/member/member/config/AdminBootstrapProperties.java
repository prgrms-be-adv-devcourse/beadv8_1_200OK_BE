package com.ok.member.member.config;

import com.ok.member.member.service.dto.AdminAccountCommand;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 앱 시작 시 만들 최초 관리자 계정의 설정값.
 *
 * <p>application.yaml의 {@code admin-bootstrap.*} 값이 이 record에 바인딩된다. 값은 {@code .env}로만 주입하고 저장소에 두지 않는다.
 * <pre>
 * admin-bootstrap:
 *   login-id: ${ADMIN_BOOTSTRAP_LOGIN_ID:}
 *   password: ${ADMIN_BOOTSTRAP_PASSWORD:}
 *   email: ${ADMIN_BOOTSTRAP_EMAIL:}
 * </pre>
 * 아이디가 비어 있으면 이 설정은 등록되지 않는다({@link AdminBootstrapConfig}). 아이디를 넣었으면 나머지 값도 모두 필요하다.
 *
 * <p>값이 잘못되면 앱 시작 시점에 바로 실패하도록 생성자에서 검증한다.
 * 설정을 반만 넣은 실수를 조용히 넘기면, 관리자가 없는 채로 서버가 떠서 원인을 찾기 어렵다.
 *
 * @param loginId  로그인 아이디. 회원가입과 같이 앞뒤 공백을 지우고 소문자로 바꾼다
 * @param password 비밀번호 평문. 회원가입과 같은 규칙을 따른다. 오류 메시지와 로그에 남기지 않는다
 * @param email    이메일. 회원가입과 같이 앞뒤 공백을 지우고 소문자로 바꾼다
 */
@ConfigurationProperties(prefix = "admin-bootstrap")
public record AdminBootstrapProperties(
        String loginId,
        String password,
        String email
) {

    // 회원가입 요청(SignupRequest)과 같은 규칙이다. 다르면 관리자를 만들 수는 있어도 로그인 규칙과 어긋날 수 있어 함께 바꾼다
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9]{4,20}");
    private static final Pattern PASSWORD = Pattern.compile("(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}");
    private static final int EMAIL_MAX_LENGTH = 255;

    /**
     * 설정 바인딩 직후 값을 정규화하고 검증한다.
     * 아이디와 이메일은 로그인·회원가입과 같은 규칙으로 정규화해야, 만든 아이디로 로그인할 수 있다.
     */
    public AdminBootstrapProperties {
        loginId = toLowerCaseTrimmed(loginId);
        email = toLowerCaseTrimmed(email);

        if (loginId == null || !LOGIN_ID.matcher(loginId).matches()) {
            throw new IllegalArgumentException("ADMIN_BOOTSTRAP_LOGIN_ID는 영문 소문자와 숫자로 4~20자여야 합니다.");
        }
        // 비밀번호는 오류 메시지에도 값을 넣지 않는다
        if (password == null || !PASSWORD.matcher(password).matches()) {
            throw new IllegalArgumentException(
                    "ADMIN_BOOTSTRAP_PASSWORD는 영문, 숫자, 특수문자를 모두 포함해 8~20자여야 합니다.");
        }
        if (email == null || email.length() > EMAIL_MAX_LENGTH || !EMAIL.matcher(email).matches()) {
            throw new IllegalArgumentException("ADMIN_BOOTSTRAP_EMAIL 형식이 올바르지 않습니다.");
        }
    }

    public AdminAccountCommand toCommand() {
        return new AdminAccountCommand(loginId, password, email);
    }

    /**
     * 설정값을 로그로 출력해도 비밀번호 평문이 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "AdminBootstrapProperties[loginId=%s, password=****, email=%s]".formatted(loginId, email);
    }

    private static String toLowerCaseTrimmed(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

}
