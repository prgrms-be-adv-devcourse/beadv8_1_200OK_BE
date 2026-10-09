package com.ok.member.member.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.member.domain.MemberErrorCode;
import com.ok.member.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
public class MemberDuplicateValidatorTest {

    private static final String LOGIN_ID = "tester01";
    private static final String EMAIL = "tester@example.com";

    @Mock
    MemberRepository memberRepository;

    @InjectMocks
    MemberDuplicateValidator validator;

    @Test
    @DisplayName("중복이 없으면 예외가 발생하지 않는다")
    void validate_noDuplicate() {
        // given
        // 모든 exists 조회가 기본값 false를 반환한다

        // when & then
        assertThatCode(() -> validator.validate(LOGIN_ID, EMAIL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 사용 중인 아이디면 DUPLICATE_LOGIN_ID 예외가 발생한다")
    void validate_duplicateLoginId() {
        // given
        given(memberRepository.existsByLoginId(LOGIN_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(LOGIN_ID, EMAIL))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.DUPLICATE_LOGIN_ID);
    }

    @Test
    @DisplayName("이미 사용 중인 이메일이면 DUPLICATE_EMAIL 예외가 발생한다")
    void validate_duplicateEmail() {
        // given
        given(memberRepository.existsByEmail(EMAIL)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(LOGIN_ID, EMAIL))
                .isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("unique 제약 위반은 제약 이름에 맞는 중복 에러로 변환한다")
    void toDuplicateException_knownConstraint() {
        // given
        DataIntegrityViolationException e = new DataIntegrityViolationException(
                "Duplicate entry 'tester@example.com' for key 'member.uk_member_email'");

        // when
        RuntimeException result = validator.toDuplicateException(e);

        // then
        assertThat(result).isInstanceOf(RestApiException.class)
                .extracting("errorCode")
                .isEqualTo(MemberErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    @DisplayName("회원 unique 제약이 아닌 무결성 위반은 원래 예외를 그대로 반환한다")
    void toDuplicateException_unknownConstraint() {
        // given
        DataIntegrityViolationException e = new DataIntegrityViolationException("Column 'name' cannot be null");

        // when
        RuntimeException result = validator.toDuplicateException(e);

        // then
        assertThat(result).isSameAs(e);
    }

}
