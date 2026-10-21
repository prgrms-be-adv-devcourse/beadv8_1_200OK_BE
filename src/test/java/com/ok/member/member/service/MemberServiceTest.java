package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.exception.MemberErrorCode;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.MemberInfoUpdateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    MemberRepository memberRepository;

    MemberService memberService;

    @BeforeEach
    void setUp() {
        memberService = new MemberService(memberRepository);
    }

    @Test
    void 회원이_없으면_조회할_때_MEMBER_NOT_FOUND를_던진다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> memberService.getMyInfo(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void 탈퇴한_회원이면_조회할_때_MEMBER_NOT_FOUND를_던진다() {
        // given
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member(MemberStatus.WITHDRAWN)));

        // when & then
        assertThatThrownBy(() -> memberService.getMyInfo(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    @Test
    void 탈퇴한_회원이면_수정하지_않고_MEMBER_NOT_FOUND를_던진다() {
        // given
        Member member = member(MemberStatus.WITHDRAWN);
        given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(member));

        // when & then
        assertThatThrownBy(() -> memberService.updateMyInfo(MEMBER_ID, new MemberInfoUpdateCommand("이서연", "주말장보기")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND));
        assertThat(member.getNickname()).isEqualTo("길동이");
    }

    private Member member(MemberStatus status) {
        Member member = Member.createUser("tester01", "{bcrypt}encoded", "tester@example.com",
                "홍길동", "길동이", LocalDateTime.of(2026, 10, 19, 12, 0));
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);
        ReflectionTestUtils.setField(member, "status", status);
        return member;
    }

}
