package com.ok.product.product.service;

import org.springframework.stereotype.Component;

import com.ok.common.html.HtmlSanitizer;

// 상품 상세설명 HTML 정제기 (싱글톤)
@Component
class ProductDescriptionSanitizer {

	private final HtmlSanitizer sanitizer;

	ProductDescriptionSanitizer(ProductImageUrlValidator imageUrlValidator) {
		this.sanitizer = new HtmlSanitizer(imageUrlValidator::isAllowed);
	}

	String sanitize(String html) {
		return sanitizer.sanitize(html);
	}
}
