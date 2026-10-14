package com.ok.member.auth.controller.dto;

import com.ok.member.member.controller.dto.SignupRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원가입과 로그인의 아이디 정규화 규칙이 같은지 확인한다.
 * 두 DTO는 모듈 분리를 대비해 규칙을 각자 갖고 있어서, 한쪽만 바뀌면 가입한 아이디로 로그인할 수 없게 된다.
 * 이 테스트가 그런 어긋남을 잡는다. auth를 별도 모듈로 분리하면 두 모듈의 코드를 함께 참조할 수 없으므로,
 * 그때는 회원가입 후 로그인하는 통합 테스트로 옮긴다.
 */
class LoginIdNormalizationConsistencyTest {

    @ParameterizedTest
    @MethodSource("정규화가_필요한_아이디")
    void 같은_아이디를_입력하면_회원가입과_로그인이_같은_값으로_정규화한다(String loginId) {
        // given
        SignupRequest signupRequest = new SignupRequest(
                loginId, "password1!", "tester@example.com", "홍길동", "길동이", true, true);

        // when
        LoginRequest sut = new LoginRequest(loginId, "password1!");

        // then
        assertThat(sut.loginId()).isEqualTo(signupRequest.loginId());
    }

    static Stream<String> 정규화가_필요한_아이디() {
        return Stream.of("tester01", "Tester01", "  TESTER01  ", "ADMIN01");
    }

}
