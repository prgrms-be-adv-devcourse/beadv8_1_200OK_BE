package com.ok.member.auth.application.service;

import com.ok.common.security.TokenRoles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRoleMapperTest {

    // 두 인자가 모두 boolean이라 순서가 바뀌어도 컴파일되므로 네 가지 조합을 모두 확인한다
    @ParameterizedTest
    @MethodSource("관리자_여부와_판매자_승인_여부별_토큰_권한")
    void 관리자_여부와_판매자_승인_여부를_토큰_권한_목록으로_바꾼다(
            boolean admin, boolean approvedSeller, List<String> expected) {
        // when
        List<String> sut = TokenRoleMapper.toTokenRoles(admin, approvedSeller);

        // then
        assertThat(sut).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> 관리자_여부와_판매자_승인_여부별_토큰_권한() {
        return Stream.of(
                Arguments.of(false, false, List.of(TokenRoles.BUYER)),
                // 판매자도 구매할 수 있어 구매자 권한을 유지한다
                Arguments.of(false, true, List.of(TokenRoles.BUYER, TokenRoles.SELLER)),
                // 관리자는 운영 전용 계정이라 승인 여부와 관계없이 구매자·판매자 권한을 갖지 않는다
                Arguments.of(true, false, List.of(TokenRoles.ADMIN)),
                Arguments.of(true, true, List.of(TokenRoles.ADMIN))
        );
    }

}
