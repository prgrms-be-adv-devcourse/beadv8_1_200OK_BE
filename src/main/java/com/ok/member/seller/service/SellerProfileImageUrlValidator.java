package com.ok.member.seller.service;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.member.seller.exception.SellerErrorCode;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 판매자 프로필 사진 URL을 저장용 object key로 바꾼다.
 * 클라이언트는 미디어 업로드 API({@code POST /api/v1/media/SELLER_PROFILE})가 돌려준 URL을 보내고, DB에는 key만 저장한다.
 */
@Component
class SellerProfileImageUrlValidator {

    // 업로드한 이미지는 jpg로 바뀐다.
    // 프로필은 사진만 받으므로 영상(mp4)은 막고, 길이 상한으로 긴 키가 컬럼(255자)을 넘기 전에 422로 막는다
    private static final Pattern OBJECT_KEY = Pattern.compile("seller_profile/[A-Za-z0-9-]{1,200}\\.jpg");

    private final MediaUrlResolver mediaUrlResolver;

    SellerProfileImageUrlValidator(MediaUrlResolver mediaUrlResolver) {
        this.mediaUrlResolver = mediaUrlResolver;
    }

    /**
     * 프로필 사진 URL을 object key로 바꾼다. 우리 저장소의 판매자 프로필 경로가 아니면 거절한다.
     * 다른 사람이 올린 사진인지는 확인하지 않는다.
     *
     * @return object key. URL이 {@code null}이면 {@code null}(사진 없음)
     * @throws RestApiException 판매자 프로필용으로 업로드한 사진 URL이 아니면 {@code INVALID_PROFILE_IMAGE_URL}
     */
    String toObjectKey(String imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        return mediaUrlResolver.toObjectKey(imageUrl)
                .filter(objectKey -> OBJECT_KEY.matcher(objectKey).matches())
                .orElseThrow(() -> new RestApiException(SellerErrorCode.INVALID_PROFILE_IMAGE_URL));
    }

}
