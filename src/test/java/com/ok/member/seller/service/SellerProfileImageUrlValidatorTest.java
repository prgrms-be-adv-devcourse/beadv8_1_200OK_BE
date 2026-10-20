package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.member.seller.exception.SellerErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SellerProfileImageUrlValidatorTest {

    private static final String BASE_URL = "https://bucket.s3.ap-northeast-2.amazonaws.com/";
    private static final String IMAGE_KEY = "seller_profile/3f2a9c1e-7b4d-4e8a-9f0c-1d2e3f4a5b6c.jpg";

    // URL 변환은 값 계산뿐이라 실제 객체를 쓴다
    private final SellerProfileImageUrlValidator sut =
            new SellerProfileImageUrlValidator(new MediaUrlResolver("bucket", "ap-northeast-2"));

    @Test
    void 판매자_프로필용으로_올린_사진_URL은_object_key로_바꾼다() {
        // when
        String objectKey = sut.toObjectKey(BASE_URL + IMAGE_KEY);

        // then
        assertThat(objectKey).isEqualTo(IMAGE_KEY);
    }

    @Test
    void 사진_URL이_없으면_사진이_없는_것으로_본다() {
        // when
        String objectKey = sut.toObjectKey(null);

        // then
        assertThat(objectKey).isNull();
    }

    @ParameterizedTest
    @MethodSource("판매자_프로필용이_아닌_사진_URL")
    void 판매자_프로필용으로_올린_사진이_아니면_거절한다(String imageUrl) {
        // when & then
        assertThatThrownBy(() -> sut.toObjectKey(imageUrl))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(SellerErrorCode.INVALID_PROFILE_IMAGE_URL));
    }

    static Stream<String> 판매자_프로필용이_아닌_사진_URL() {
        return Stream.of(
                "https://evil.example.com/" + IMAGE_KEY,                       // 다른 저장소
                BASE_URL + "product/3f2a9c1e.jpg",                             // 다른 용도의 경로
                BASE_URL + "seller_profile/3f2a9c1e.mp4",                      // 영상 (프로필은 사진만)
                BASE_URL + "seller_profile/../product/3f2a9c1e.jpg",           // 경로 이동
                BASE_URL + "seller_profile/.jpg",                              // 파일명 없음
                BASE_URL + "seller_profile/" + "a".repeat(201) + ".jpg"        // 컬럼(255자)을 넘길 수 있는 긴 키
        );
    }
}
