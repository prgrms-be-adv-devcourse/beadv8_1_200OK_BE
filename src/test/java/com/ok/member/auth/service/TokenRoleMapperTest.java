package com.ok.member.auth.service;

import com.ok.common.security.TokenRoles;
import com.ok.member.MemberRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenRoleMapperTest {

    @ParameterizedTest
    @MethodSource("회원_권한별_토큰_권한")
    void 회원_권한을_토큰_권한_목록으로_바꾼다(MemberRole role, List<String> expected) {
        // when
        List<String> sut = TokenRoleMapper.toTokenRoles(role);

        // then
        assertThat(sut).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> 회원_권한별_토큰_권한() {
        return Stream.of(
                Arguments.of(MemberRole.BUYER, List.of(TokenRoles.BUYER)),
                // 판매자도 구매할 수 있으므로 구매자 권한을 함께 갖는다
                Arguments.of(MemberRole.SELLER, List.of(TokenRoles.BUYER, TokenRoles.SELLER)),
                Arguments.of(MemberRole.ADMIN, List.of(TokenRoles.ADMIN))
        );
    }

    @Test
    void 회원_권한이_null이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> TokenRoleMapper.toTokenRoles(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("role은 필수입니다.");
    }

}
