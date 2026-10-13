package com.ok.product.product.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 상품 이미지와 상품 본문 이미지의 URL이 우리 버킷의 상품 이미지 경로인지 판단한다.
@Component
class ProductImageUrlValidator {

	// 상품 이미지를 저장하는 경로 (MediaPurpose.PRODUCT)
	private static final String PRODUCT_IMAGE_DIRECTORY = "product/";

	private final String allowedPrefix;

	public ProductImageUrlValidator(@Value("${app.s3.bucket}") String bucket, @Value("${app.s3.region}") String region) {
		this.allowedPrefix = "https://" + bucket + ".s3." + region + ".amazonaws.com/" + PRODUCT_IMAGE_DIRECTORY;
	}

	// ..가 있으면 브라우저가 경로를 정리하면서 product/ 밖의 파일을 가리킬 수 있어 거부한다
	public boolean isAllowed(String url) {
		return url != null && url.startsWith(allowedPrefix) && !url.contains("..");
	}
}
