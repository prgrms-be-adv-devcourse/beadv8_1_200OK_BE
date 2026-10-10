package com.ok.media.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MediaPolicyTest {

	@Test
	void jpeg와_png는_허용_이미지_타입이다() {
		// given
		String jpeg = "image/jpeg";
		String png = "image/png";

		// when
		boolean jpegAllowed = MediaPolicy.isAllowedImageContentType(jpeg);
		boolean pngAllowed = MediaPolicy.isAllowedImageContentType(png);

		// then
		assertThat(jpegAllowed).isTrue();
		assertThat(pngAllowed).isTrue();
	}

	@Test
	void mp4는_허용_영상_타입이다() {
		// given
		String mp4 = "video/mp4";

		// when
		boolean allowed = MediaPolicy.isAllowedVideoContentType(mp4);

		// then
		assertThat(allowed).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"image/gif", "application/pdf"})
	void 지원하지_않는_타입은_거부한다(String contentType) {
		// when
		boolean imageAllowed = MediaPolicy.isAllowedImageContentType(contentType);
		boolean videoAllowed = MediaPolicy.isAllowedVideoContentType(contentType);

		// then
		assertThat(imageAllowed).isFalse();
		assertThat(videoAllowed).isFalse();
	}

	@Test
	void content_type이_null이면_거부한다() {
		// given
		String contentType = null;

		// when
		boolean imageAllowed = MediaPolicy.isAllowedImageContentType(contentType);
		boolean videoAllowed = MediaPolicy.isAllowedVideoContentType(contentType);

		// then
		assertThat(imageAllowed).isFalse();
		assertThat(videoAllowed).isFalse();
	}
}
