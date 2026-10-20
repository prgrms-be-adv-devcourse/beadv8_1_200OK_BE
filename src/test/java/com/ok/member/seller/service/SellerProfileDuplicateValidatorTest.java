package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SellerProfileDuplicateValidatorTest {

    private static final Long MEMBER_ID = 1L;

    @Mock
    SellerProfileRepository sellerProfileRepository;

    @InjectMocks
    SellerProfileDuplicateValidator validator;

    @Test
    void 이_회원의_프로필이_없으면_예외가_발생하지_않는다() {
        // given
        given(sellerProfileRepository.existsByMemberId(MEMBER_ID)).willReturn(false);

        // when & then
        assertThatCode(() -> validator.validate(MEMBER_ID)).doesNotThrowAnyException();
    }

    @Test
    void 이_회원의_프로필이_이미_있으면_SELLER_PROFILE_ALREADY_EXISTS가_발생한다() {
        // given
        given(sellerProfileRepository.existsByMemberId(MEMBER_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> validator.validate(MEMBER_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS));
    }

    @Test
    void 회원_유니크_제약_위반은_중복_등록_에러로_바꾼다() {
        // when
        RuntimeException sut = validator.toDuplicateException(violation("uk_seller_profile_member_id"));

        // then
        assertThat(sut).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS));
    }

    @Test
    void 유니크_제약이_아닌_무결성_위반은_원래_예외를_그대로_반환한다() {
        // given
        DataIntegrityViolationException violation = violation("fk_seller_profile_member_id");

        // when
        RuntimeException sut = validator.toDuplicateException(violation);

        // then
        assertThat(sut).isSameAs(violation);
    }

    private static DataIntegrityViolationException violation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException("Duplicate entry for key '" + constraintName + "'"));
    }

}
