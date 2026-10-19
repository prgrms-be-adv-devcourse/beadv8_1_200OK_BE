package com.ok.member.member.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class MemberTest {

    @Test
    void 관리자를_만들면_관리자_유형의_활성_회원이고_만든_일시를_동의_일시로_기록한다() {
        // given
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 19, 12, 0);

        // when
        Member sut = Member.createAdmin("admin01", "{bcrypt}encoded", "admin@example.com",
                "관리자", "관리자", createdAt);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getType()).isEqualTo(MemberType.ADMIN);
            softly.assertThat(sut.getStatus()).isEqualTo(MemberStatus.ACTIVE);
            softly.assertThat(sut.getLoginId()).isEqualTo("admin01");
            softly.assertThat(sut.getPassword()).isEqualTo("{bcrypt}encoded");
            softly.assertThat(sut.getTermsAgreedAt()).isEqualTo(createdAt);
            softly.assertThat(sut.getPrivacyAgreedAt()).isEqualTo(createdAt);
        });
    }
}
