package com.ok.product.product.service;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 상품 이미지와 상품 본문 이미지의 URL이 우리 버킷의 상품 이미지 경로인지 판단한다.
// 누가 올렸는지는 보지 않는다. 다른 판매자가 올린 이미지도 같은 경로라면 허용한다
@Component
class ProductImageUrlValidator {

	// 상품 이미지를 저장하는 경로 (MediaPurpose.PRODUCT)
	private static final String PRODUCT_IMAGE_DIRECTORY = "product/";

	// 미디어 모듈이 만드는 키(product/{UUID}.jpg)의 파일명 부분
	private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9-]+\\.[A-Za-z0-9]+");

	private final String allowedPrefix;

	public ProductImageUrlValidator(@Value("${app.s3.bucket}") String bucket, @Value("${app.s3.region}") String region) {
		this.allowedPrefix = "https://" + bucket + ".s3." + region + ".amazonaws.com/" + PRODUCT_IMAGE_DIRECTORY;
	}

	public boolean isAllowed(String url) {
		return url != null
			&& url.startsWith(allowedPrefix)
			&& FILE_NAME.matcher(url.substring(allowedPrefix.length())).matches();
	}
}
