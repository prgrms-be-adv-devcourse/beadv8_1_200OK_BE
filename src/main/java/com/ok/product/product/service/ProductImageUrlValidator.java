package com.ok.product.product.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 상품 본문에서 이미지의 URL이 우리 버킷의 경로인지 판단한다.
@Component
class ProductImageUrlValidator {

	private final String allowedPrefix;

	public ProductImageUrlValidator(@Value("${app.s3.bucket}") String bucket, @Value("${app.s3.region}") String region) {
		this.allowedPrefix = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
	}

	public boolean isAllowed(String url) {
		return url != null && url.startsWith(allowedPrefix);
	}
}
