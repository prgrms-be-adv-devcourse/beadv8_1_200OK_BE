package com.ok.media.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

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

	@ParameterizedTest
	@MethodSource("디코딩_허용_해상도")
	void 가로_세로_8000px_이하이고_총_4천만_픽셀_이하면_디코딩을_허용한다(int width, int height) {
		// when
		boolean allowed = MediaPolicy.isDecodableResolution(width, height);

		// then
		assertThat(allowed).isTrue();
	}

	static Stream<Arguments> 디코딩_허용_해상도() {
		return Stream.of(
				Arguments.of(1, 1),
				Arguments.of(8000, 5000),
				Arguments.of(5000, 8000)
		);
	}

	@ParameterizedTest
	@MethodSource("디코딩_거부_해상도")
	void 한_변이_8000px을_넘거나_총_픽셀이_4천만을_넘으면_디코딩을_거부한다(int width, int height) {
		// when
		boolean allowed = MediaPolicy.isDecodableResolution(width, height);

		// then
		assertThat(allowed).isFalse();
	}

	static Stream<Arguments> 디코딩_거부_해상도() {
		return Stream.of(
				Arguments.of(8001, 1),
				Arguments.of(1, 8001),
				Arguments.of(8000, 5001),
				Arguments.of(7000, 7000)
		);
	}
}
