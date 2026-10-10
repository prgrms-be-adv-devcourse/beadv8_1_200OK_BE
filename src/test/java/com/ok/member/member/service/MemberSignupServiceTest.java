package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.MemberRegisteredEvent;
import com.ok.member.MemberRole;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberErrorCode;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.SignupCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class MemberSignupServiceTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    MemberDuplicateValidator duplicateValidator;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    MemberSignupService memberSignupService;

    private final SignupCommand command = new SignupCommand(
            "tester01", "password1!",
            "tester@example.com", "홍길동", "길동이");

    @Test
    @DisplayName("회원가입에 성공하면 인코딩된 비밀번호로 저장하고 이벤트를 발행한다")
    void signup_success() {
        // given
        given(passwordEncoder.encode("password1!")).willReturn("{bcrypt}encoded");
        given(memberRepository.saveAndFlush(any(Member.class))).willAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 1L);
            return member;
        });

        // when
        Long memberId = memberSignupService.signup(command);

        // then
        assertThat(memberId).isEqualTo(1L);
        verify(duplicateValidator).validate("tester01", "tester@example.com");

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).saveAndFlush(memberCaptor.capture());
        Member saved = memberCaptor.getValue();
        assertThat(saved.getPassword()).isEqualTo("{bcrypt}encoded");
        assertThat(saved.getRole()).isEqualTo(MemberRole.BUYER);
        assertThat(saved.getStatus()).isEqualTo(MemberStatus.ACTIVE);
        assertThat(saved.getTermsAgreedAt()).isNotNull();
        assertThat(saved.getPrivacyAgreedAt()).isNotNull();

        verify(eventPublisher).publishEvent(new MemberRegisteredEvent(1L, MemberRole.BUYER));
    }

    @Test
    @DisplayName("중복 검증에 실패하면 저장하지 않고 이벤트도 발행하지 않는다")
    void signup_duplicate() {
        // given
        willThrow(new RestApiException(MemberErrorCode.DUPLICATE_EMAIL))
                .given(duplicateValidator).validate(any(), any());

        // when & then
        assertThatThrownBy(() -> memberSignupService.signup(command))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
        verify(memberRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("저장 중 unique 제약을 위반하면 변환된 예외를 던지고 이벤트를 발행하지 않는다")
    void signup_uniqueConstraintViolation() {
        // given
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "Duplicate entry 'tester@example.com' for key 'member.uk_member_email'");
        given(passwordEncoder.encode(any())).willReturn("{bcrypt}encoded");
        given(memberRepository.saveAndFlush(any(Member.class))).willThrow(violation);
        given(duplicateValidator.toDuplicateException(violation))
                .willReturn(new RestApiException(MemberErrorCode.DUPLICATE_EMAIL));

        // when & then
        assertThatThrownBy(() -> memberSignupService.signup(command))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
        verify(eventPublisher, never()).publishEvent(any());
    }

}
