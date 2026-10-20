package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.member.seller.domain.SellerProfile;
import com.ok.member.seller.exception.SellerErrorCode;
import com.ok.member.seller.repository.SellerProfileRepository;
import com.ok.member.seller.service.dto.SellerProfileCommand;
import com.ok.member.seller.service.dto.SellerProfileResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SellerProfileServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String IMAGE_KEY = "seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg";

    @Mock
    SellerProfileRepository sellerProfileRepository;

    SellerProfileService sellerProfileService;

    @BeforeEach
    void setUp() {
        // URL 변환은 값 계산뿐이라 실제 객체를 쓴다
        MediaUrlResolver mediaUrlResolver = new MediaUrlResolver("bucket", "ap-northeast-2");
        sellerProfileService = new SellerProfileService(sellerProfileRepository,
                new SellerProfileImageUrlValidator(mediaUrlResolver), mediaUrlResolver);
    }

    @Test
    void 등록하면_사진은_key로_저장하고_URL로_돌려준다() {
        // given
        given(sellerProfileRepository.saveAndFlush(any(SellerProfile.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        SellerProfileResult result = sellerProfileService.register(MEMBER_ID, command(BASE_URL + IMAGE_KEY));

        // then
        ArgumentCaptor<SellerProfile> captor = ArgumentCaptor.forClass(SellerProfile.class);
        then(sellerProfileRepository).should().saveAndFlush(captor.capture());
        SellerProfile sut = captor.getValue();

        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(MEMBER_ID);
            softly.assertThat(sut.getProfileImageKey()).isEqualTo(IMAGE_KEY);
            softly.assertThat(result.profileImageUrl()).isEqualTo(BASE_URL + IMAGE_KEY);
        });
    }

    @Test
    void 이미_프로필이_있으면_다시_등록하지_않는다() {
        // given
        given(sellerProfileRepository.existsByMemberId(MEMBER_ID)).willReturn(true);

        // when & then
        assertThatThrownBy(() -> sellerProfileService.register(MEMBER_ID, command(null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS));
        then(sellerProfileRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 등록_버튼을_동시에_눌러_유니크_제약에_걸리면_중복_등록_오류로_바꾼다() {
        // given: 사전 확인은 둘 다 통과했다
        given(sellerProfileRepository.saveAndFlush(any(SellerProfile.class)))
                .willThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLIntegrityConstraintViolationException(
                                "Duplicate entry for key 'uk_seller_profile_member_id'")));

        // when & then
        assertThatThrownBy(() -> sellerProfileService.register(MEMBER_ID, command(null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_ALREADY_EXISTS));
    }

    @Test
    void 판매자_프로필용으로_올린_사진이_아니면_등록하지_않는다() {
        // when & then
        assertThatThrownBy(() -> sellerProfileService.register(MEMBER_ID, command(BASE_URL + "product/a.jpg")))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_PROFILE_IMAGE_URL));
        then(sellerProfileRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 수정할_때_사진_URL이_없으면_사진을_지운다() {
        // given
        SellerProfile profile = SellerProfile.register(
                MEMBER_ID, "청송 햇살농원", "김영수", "054-873-2210", null, IMAGE_KEY);
        given(sellerProfileRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(profile));

        // when
        SellerProfileResult result = sellerProfileService.update(MEMBER_ID, command(null));

        // then
        assertSoftly(softly -> {
            softly.assertThat(profile.getProfileImageKey()).isNull();
            softly.assertThat(result.profileImageUrl()).isNull();
        });
    }

    @Test
    void 프로필을_등록하지_않았으면_수정할_수_없다() {
        // given
        given(sellerProfileRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sellerProfileService.update(MEMBER_ID, command(null)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.SELLER_PROFILE_NOT_FOUND));
    }

    private static SellerProfileCommand command(String profileImageUrl) {
        return new SellerProfileCommand("청송 햇살농원", "김영수", "054-873-2210",
                "3대째 사과만 키우는 청송 부남면 과수원입니다.", profileImageUrl);
    }

}
