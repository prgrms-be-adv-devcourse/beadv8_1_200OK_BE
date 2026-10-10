package com.ok.media.domain;

public enum MediaPurpose {
	// NOTE
	// 모듈 담당 하시는분 필요한 enum 만들어서 쓰세요.
	// 값으로 버킷의 매칭되는 디렉토리에 미디어 리소스 저장함
	PRODUCT("product"),
	PRODUCT_REVIEW("product_review"),
	MEMBER_PROFILE("member_profile"),
	SELLER_PROFILE("seller_profile");

	private final String directory;
	MediaPurpose(String directory) {
		this.directory = directory;
	}
	public String getDirectory() {
		return directory;
	}
}
