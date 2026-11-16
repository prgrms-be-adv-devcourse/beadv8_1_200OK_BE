package com.ok.member.member.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.stream.Stream;

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

    @ParameterizedTest
    @MethodSource("회원_상태별_탈퇴_정지_여부")
    void 회원_상태로_탈퇴_여부와_정지_여부를_판단한다(MemberStatus status, boolean withdrawn, boolean suspended) {
        // given
        Member sut = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 19, 12, 0));
        // 정지·탈퇴로 바꾸는 도메인 메서드가 아직 없어 상태를 직접 설정한다
        ReflectionTestUtils.setField(sut, "status", status);

        // when
        boolean actualWithdrawn = sut.isWithdrawn();
        boolean actualSuspended = sut.isSuspended();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actualWithdrawn).isEqualTo(withdrawn);
            softly.assertThat(actualSuspended).isEqualTo(suspended);
        });
    }

    static Stream<Arguments> 회원_상태별_탈퇴_정지_여부() {
        return Stream.of(
                Arguments.of(MemberStatus.ACTIVE, false, false),
                Arguments.of(MemberStatus.SUSPENDED, false, true),
                Arguments.of(MemberStatus.WITHDRAWN, true, false)
        );
    }

    @Test
    void 관리자로_만든_회원만_관리자다() {
        // given
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 19, 12, 0);
        Member admin = Member.createAdmin("admin01", "{bcrypt}encoded", "admin@example.com", "관리자", "관리자", createdAt);
        Member user = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com", "홍길동", "길동이", createdAt);

        // when
        boolean adminIsAdmin = admin.isAdmin();
        boolean userIsAdmin = user.isAdmin();

        // then
        assertSoftly(softly -> {
            softly.assertThat(adminIsAdmin).isTrue();
            softly.assertThat(userIsAdmin).isFalse();
        });
    }

    @Test
    void 이름과_닉네임을_수정하면_아이디와_이메일은_그대로다() {
        // given
        Member sut = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 19, 12, 0));

        // when
        sut.updateInfo("이서연", "주말장보기");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getName()).isEqualTo("이서연");
            softly.assertThat(sut.getNickname()).isEqualTo("주말장보기");
            softly.assertThat(sut.getLoginId()).isEqualTo("tester01");
            softly.assertThat(sut.getEmail()).isEqualTo("tester@example.com");
        });
    }

}
