package com.ok.member.auth.service;

import com.ok.common.security.TokenRoles;
import com.ok.member.member.domain.MemberType;
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
    @MethodSource("회원_유형별_토큰_권한")
    void 회원_유형을_토큰_권한_목록으로_바꾼다(MemberType type, List<String> expected) {
        // when
        List<String> sut = TokenRoleMapper.toTokenRoles(type, false);

        // then
        assertThat(sut).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> 회원_유형별_토큰_권한() {
        return Stream.of(
                Arguments.of(MemberType.USER, List.of(TokenRoles.BUYER)),
                // 관리자는 운영 전용 계정이라 구매자·판매자 권한을 갖지 않는다
                Arguments.of(MemberType.ADMIN, List.of(TokenRoles.ADMIN))
        );
    }

    @Test
    void 회원_유형이_null이면_예외가_발생한다() {
        // when & then
        assertThatThrownBy(() -> TokenRoleMapper.toTokenRoles(null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("type은 필수입니다.");
    }

}
