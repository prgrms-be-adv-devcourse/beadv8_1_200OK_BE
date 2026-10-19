package com.ok.member.member.config;

import com.ok.member.member.service.dto.AdminAccountCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapPropertiesTest {

    private static final String PASSWORD = "Admin123!";

    @Test
    void 아이디와_이메일은_회원가입처럼_앞뒤_공백을_지우고_소문자로_바꾼다() {
        // when
        AdminBootstrapProperties sut = new AdminBootstrapProperties(" Admin01 ", PASSWORD, " Admin@Example.com ");
        // then: 로그인도 같은 규칙으로 정규화하므로, 바꾸지 않으면 만든 아이디로 로그인할 수 없다
        assertThat(sut.toCommand()).isEqualTo(new AdminAccountCommand("admin01", PASSWORD, "admin@example.com"));
    }

    @Test
    void 비밀번호는_공백도_비밀번호의_일부라_바꾸지_않는다() {
        // when
        AdminBootstrapProperties sut = new AdminBootstrapProperties("admin01", " Admin123! ", "admin@example.com");
        // then
        assertThat(sut.password()).isEqualTo(" Admin123! ");
    }

    @ParameterizedTest
    @MethodSource("잘못된_설정값")
    void 설정값이_빠졌거나_형식이_틀리면_앱_시작을_막고_오류_메시지에_비밀번호를_남기지_않는다(
            String loginId, String password, String email, String expectedMessagePart) {
        // when & then
        assertThatThrownBy(() -> new AdminBootstrapProperties(loginId, password, email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessagePart)
                .satisfies(e -> {
                    if (password != null && !password.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(password);
                    }
                });
    }

    static Stream<Arguments> 잘못된_설정값() {
        return Stream.of(
                Arguments.of("ad", PASSWORD, "admin@example.com", "ADMIN_BOOTSTRAP_LOGIN_ID"),
                Arguments.of("admin_01", PASSWORD, "admin@example.com", "ADMIN_BOOTSTRAP_LOGIN_ID"),
                // 아이디만 넣고 비밀번호·이메일을 빠뜨린 경우
                Arguments.of("admin01", null, "admin@example.com", "ADMIN_BOOTSTRAP_PASSWORD"),
                Arguments.of("admin01", "", "admin@example.com", "ADMIN_BOOTSTRAP_PASSWORD"),
                Arguments.of("admin01", "admin1234", "admin@example.com", "ADMIN_BOOTSTRAP_PASSWORD"),
                Arguments.of("admin01", PASSWORD, null, "ADMIN_BOOTSTRAP_EMAIL"),
                Arguments.of("admin01", PASSWORD, "admin@example", "ADMIN_BOOTSTRAP_EMAIL")
        );
    }

    @Test
    void 설정값의_문자열_표현에_비밀번호를_남기지_않는다() {
        // given
        AdminBootstrapProperties properties = new AdminBootstrapProperties("admin01", PASSWORD, "admin@example.com");
        // when
        String sut = properties.toString();
        // then
        assertThat(sut).doesNotContain(PASSWORD).contains("password=****");
    }

}
