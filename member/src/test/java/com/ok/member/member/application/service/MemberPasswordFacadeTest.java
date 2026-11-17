package com.ok.member.member.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.application.dto.PasswordChangeCommand;
import com.ok.member.member.exception.MemberErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MemberPasswordFacadeTest {

    private static final Long MEMBER_ID = 1L;
    private static final String CURRENT_HASH = "{bcrypt}current";

    @Mock
    MemberService memberService;

    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    MemberPasswordFacade memberPasswordFacade;

    @Test
    void 현재_비밀번호가_맞으면_확인에_쓴_해시와_인코딩한_새_비밀번호로_바꾼다() {
        // given: 서비스가 그사이 비밀번호가 바뀌었는지 볼 수 있도록 확인에 쓴 해시를 그대로 넘겨야 한다
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("password1!", CURRENT_HASH)).willReturn(true);
        given(passwordEncoder.encode("newPassword1!")).willReturn("{bcrypt}new");

        // when
        memberPasswordFacade.changePassword(MEMBER_ID, new PasswordChangeCommand("password1!", "newPassword1!"));

        // then
        then(memberService).should().changePassword(MEMBER_ID, CURRENT_HASH, "{bcrypt}new");
    }

    @Test
    void 현재_비밀번호가_틀리면_INVALID_CURRENT_PASSWORD를_던지고_바꾸지_않는다() {
        // given
        given(memberService.findPasswordHash(MEMBER_ID)).willReturn(CURRENT_HASH);
        given(passwordEncoder.matches("wrong1!", CURRENT_HASH)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> memberPasswordFacade.changePassword(MEMBER_ID,
                new PasswordChangeCommand("wrong1!", "newPassword1!")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.INVALID_CURRENT_PASSWORD));
        then(memberService).should(never()).changePassword(anyLong(), anyString(), anyString());
        then(passwordEncoder).should(never()).encode(any());
    }

}
