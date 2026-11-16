package com.ok.media.service.dto;

import com.ok.media.domain.MediaPurpose;

// API로 주고받는 업로드 용도 값
public enum MediaPurposeCode {
	PRODUCT,
	PRODUCT_REVIEW,
	MEMBER_PROFILE,
	SELLER_PROFILE;

	public MediaPurpose toDomain() {
		return switch (this) {
			case PRODUCT -> MediaPurpose.PRODUCT;
			case PRODUCT_REVIEW -> MediaPurpose.PRODUCT_REVIEW;
			case MEMBER_PROFILE -> MediaPurpose.MEMBER_PROFILE;
			case SELLER_PROFILE -> MediaPurpose.SELLER_PROFILE;
		};
	}
}
