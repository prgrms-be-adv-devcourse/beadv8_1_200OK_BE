package com.ok.member.member.application.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberAuthAccountTest {

    @Test
    void toString에_비밀번호_해시가_노출되지_않는다() {
        // given
        MemberAuthAccount account = new MemberAuthAccount(1L, false, false, true, "{bcrypt}encoded-hash");

        // when
        String sut = account.toString();

        // then
        assertThat(sut)
                .doesNotContain("encoded-hash")
                .contains("memberId=1", "suspended=true", "passwordHash=****");
    }

}
