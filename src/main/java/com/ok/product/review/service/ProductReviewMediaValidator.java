package com.ok.product.review.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ok.common.exception.RestApiException;
import com.ok.common.media.MediaUrlResolver;
import com.ok.product.review.domain.ProductReviewMediaSpec;
import com.ok.product.review.domain.ProductReviewMediaType;
import com.ok.product.review.exception.ProductReviewErrorCode;

// 첨부를 URL 문자열로 받으므로 외부 URL과 타입 위조를 막고 저장할 objectKey와 타입을 만든다
@Component
class ProductReviewMediaValidator {

	// media 모듈이 만드는 키 형식. 업로드 시 이미지는 jpg로 바뀌고 영상은 mp4만 받는다
	private static final Pattern OBJECT_KEY = Pattern.compile("product_review/[A-Za-z0-9-]+\\.(jpg|mp4)");

	// 요청의 타입을 믿으면 영상 개수 제한을 우회할 수 있어 확장자로 정한다
	private static final Map<String, ProductReviewMediaType> TYPE_BY_EXTENSION = Map.of(
		"jpg", ProductReviewMediaType.IMAGE,
		"mp4", ProductReviewMediaType.VIDEO);

	private final MediaUrlResolver mediaUrlResolver;

	ProductReviewMediaValidator(MediaUrlResolver mediaUrlResolver) {
		this.mediaUrlResolver = mediaUrlResolver;
	}

	ProductReviewMediaSpec toSpec(String url) {
		String objectKey = mediaUrlResolver.toObjectKey(url)
			.orElseThrow(() -> new RestApiException(ProductReviewErrorCode.INVALID_REVIEW_MEDIA_URL));
		Matcher matcher = OBJECT_KEY.matcher(objectKey);
		if (!matcher.matches()) {
			throw new RestApiException(ProductReviewErrorCode.INVALID_REVIEW_MEDIA_URL);
		}
		return new ProductReviewMediaSpec(objectKey, TYPE_BY_EXTENSION.get(matcher.group(1)));
	}
}
