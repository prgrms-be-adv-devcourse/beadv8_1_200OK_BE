package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberType;
import com.ok.member.member.exception.MemberErrorCode;
import com.ok.member.member.domain.MemberStatus;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.SignupCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MemberSignupServiceTest {

    @Mock
    MemberRepository memberRepository;

    @Mock
    MemberDuplicateValidator duplicateValidator;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    MemberEventPublisher memberEventPublisher;

    MemberSignupService memberSignupService;

    // @InjectMocks는 @Mock 필드만 생성자에 넣어 주므로 Clock이 null로 들어간다. Clock을 넣어 직접 만든다 (#93)
    // 동의 일시는 isNotNull()로만 확인하므로 실제 시계를 그대로 쓴다
    @BeforeEach
    void setUp() {
        memberSignupService = new MemberSignupService(
                memberRepository, duplicateValidator, passwordEncoder,
                memberEventPublisher, Clock.systemDefaultZone());
    }

    private final SignupCommand command = new SignupCommand(
            "tester01", "password1!", "tester@example.com", "홍길동", "길동이");

    @Test
    void 회원가입에_성공하면_인코딩된_비밀번호와_구매자_권한으로_저장하고_가입_이벤트를_발행한다() {
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
        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        then(memberRepository).should().saveAndFlush(captor.capture());
        Member sut = captor.getValue();

        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(memberId).isEqualTo(1L);
            softly.assertThat(sut.getPassword()).isEqualTo("{bcrypt}encoded");
            softly.assertThat(sut.getType()).isEqualTo(MemberType.USER);
            softly.assertThat(sut.getStatus()).isEqualTo(MemberStatus.ACTIVE);
            softly.assertThat(sut.getTermsAgreedAt()).isNotNull();
            softly.assertThat(sut.getPrivacyAgreedAt()).isNotNull();
        });
        then(memberEventPublisher).should().publishMemberRegistered(1L);
    }

    @Test
    void 중복_검증에_실패하면_저장하지_않고_이벤트도_발행하지_않는다() {
        // given
        willThrow(new RestApiException(MemberErrorCode.DUPLICATE_EMAIL))
                .given(duplicateValidator).validate(any(), any());

        // when & then
        assertThatThrownBy(() -> memberSignupService.signup(command))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
        then(memberRepository).should(never()).saveAndFlush(any());
        then(memberEventPublisher).should(never()).publishMemberRegistered(any());
    }

    @Test
    void 저장_중_unique_제약을_위반하면_중복_예외로_바꿔_던지고_이벤트를_발행하지_않는다() {
        // given
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
                "Duplicate entry 'tester@example.com' for key 'member.uk_member_email'");
        given(passwordEncoder.encode(any())).willReturn("{bcrypt}encoded");
        given(memberRepository.saveAndFlush(any(Member.class))).willThrow(violation);
        given(duplicateValidator.toDuplicateException(violation))
                .willReturn(new RestApiException(MemberErrorCode.DUPLICATE_EMAIL));

        // when & then
        assertThatThrownBy(() -> memberSignupService.signup(command))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
        then(memberEventPublisher).should(never()).publishMemberRegistered(any());
    }

}
