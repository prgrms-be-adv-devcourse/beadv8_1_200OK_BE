package com.ok.member.member.application.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLoginCredentialTest {

    @Test
    void toString에_비밀번호_해시가_노출되지_않는다() {
        // given
        MemberLoginCredential credential = new MemberLoginCredential(1L, "{bcrypt}encoded-hash");

        // when
        String sut = credential.toString();

        // then
        assertThat(sut)
                .doesNotContain("encoded-hash")
                .contains("memberId=1", "passwordHash=****");
    }

}
