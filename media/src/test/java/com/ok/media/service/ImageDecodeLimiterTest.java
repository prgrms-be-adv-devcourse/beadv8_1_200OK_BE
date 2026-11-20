package com.ok.media.service;

import com.ok.common.exception.RestApiException;
import com.ok.media.exception.MediaErrorCode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDecodeLimiterTest {

	private static final Duration SHORT_WAIT = Duration.ofMillis(10);

	@Test
	void 허가가_모두_사용_중이면_대기_시간이_지난_뒤_IMAGE_PROCESSING_BUSY_예외가_발생한다() {
		// given
		ImageDecodeLimiter limiter = new ImageDecodeLimiter(1, SHORT_WAIT);

		// when & then
		// 바깥 작업이 하나뿐인 허가를 쥔 채로 안쪽 작업이 허가를 기다린다
		assertThatThrownBy(() -> limiter.run(() -> limiter.run(() -> "inner")))
				.isInstanceOfSatisfying(RestApiException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(MediaErrorCode.IMAGE_PROCESSING_BUSY));
	}

	@Test
	void 작업이_예외로_끝나도_허가를_돌려준다() throws IOException {
		// given
		ImageDecodeLimiter limiter = new ImageDecodeLimiter(1, SHORT_WAIT);
		assertThatThrownBy(() -> limiter.run(() -> {
			throw new IOException("decode failed");
		})).isInstanceOf(IOException.class);

		// when
		String sut = limiter.run(() -> "next");

		// then
		assertThat(sut).isEqualTo("next");
	}
}
