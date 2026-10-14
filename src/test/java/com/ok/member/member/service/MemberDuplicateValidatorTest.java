package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.exception.MemberErrorCode;
import com.ok.member.member.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MemberDuplicateValidatorTest {

    private static final String LOGIN_ID = "tester01";
    private static final String EMAIL = "tester@example.com";

    @Mock
    MemberRepository memberRepository;

    @InjectMocks
    MemberDuplicateValidator validator;

    @Test
    void 아이디와_이메일이_모두_사용_중이_아니면_예외가_발생하지_않는다() {
        // given
        // exists 조회는 stub하지 않으면 false를 반환한다

        // when & then
        assertThatCode(() -> validator.validate(LOGIN_ID, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void 이미_사용_중인_아이디면_DUPLICATE_LOGIN_ID가_발생한다() {
        // given
        given(memberRepository.existsByLoginId(LOGIN_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(LOGIN_ID, EMAIL))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_LOGIN_ID));
    }

    @Test
    void 이미_사용_중인_이메일이면_DUPLICATE_EMAIL이_발생한다() {
        // given
        given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(LOGIN_ID, EMAIL))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    void unique_제약_위반은_제약_이름에_맞는_중복_에러로_바꾼다() {
        // given
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "Duplicate entry 'tester@example.com' for key 'member.uk_member_email'");

        // when
        RuntimeException sut = validator.toDuplicateException(e);

        // then
        assertThat(sut).isInstanceOfSatisfying(RestApiException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    void 회원_unique_제약이_아닌_무결성_위반은_원래_예외를_그대로_반환한다() {
        // given
        DataIntegrityViolationException e = new DataIntegrityViolationException("Column 'name' cannot be null");

        // when
        RuntimeException sut = validator.toDuplicateException(e);

        // then
        assertThat(sut).isSameAs(e);
    }

}
