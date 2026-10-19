package com.ok.media.service;

import com.ok.common.exception.RestApiException;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.exception.MediaErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 서버 한 대에서 동시에 디코딩하는 이미지 수를 제한한다.
 * 디코딩한 이미지는 원본 해상도만큼 메모리를 차지해(40MP면 약 160MB) 요청이 몰리면 OOM이 날 수 있다.
 * 업로드 횟수 제한은 저장된 미디어만 세서 실패한 요청(깨진 이미지 등)은 막지 못하므로, 메모리는 여기서 따로 지킨다.
 * 메모리는 서버마다 따로 있는 자원이라 서버별로 제한하며, 서버가 늘어나도 그대로 맞다.
 */
@Component
class ImageDecodeLimiter {

	private final Semaphore permits;
	private final Duration waitTimeout;

	ImageDecodeLimiter() {
		this(MediaPolicy.MAX_CONCURRENT_DECODES, MediaPolicy.DECODE_WAIT_TIMEOUT);
	}

	ImageDecodeLimiter(int permits, Duration waitTimeout) {
		// 먼저 기다린 요청이 먼저 처리되도록 공정 모드를 쓴다
		this.permits = new Semaphore(permits, true);
		this.waitTimeout = waitTimeout;
	}

	/**
	 * 디코딩 허가를 받아 작업을 실행하고, 끝나면 예외 여부와 상관없이 허가를 돌려준다.
	 *
	 * @throws RestApiException 대기 시간 안에 허가를 받지 못하면 {@code IMAGE_PROCESSING_BUSY}
	 */
	<T> T run(DecodeTask<T> task) throws IOException {
		acquire();
		try {
			return task.run();
		} finally {
			permits.release();
		}
	}

	private void acquire() {
		try {
			if (!permits.tryAcquire(waitTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new RestApiException(MediaErrorCode.IMAGE_PROCESSING_BUSY);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RestApiException(MediaErrorCode.IMAGE_PROCESSING_BUSY);
		}
	}

	@FunctionalInterface
	interface DecodeTask<T> {
		T run() throws IOException;
	}
}
