package com.ok.media.exception;

import com.ok.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MediaErrorCode implements ErrorCode {
	UPLOAD_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Upload limit exceeded", "업로드 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요."),
	IMAGE_PROCESSING_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Image processing busy", "이미지 처리 요청이 많습니다. 잠시 후 다시 시도해 주세요."),
	;

	private final HttpStatus httpStatus;
	private final String title;
	private final String message;

	@Override
	public HttpStatus status() {
		return httpStatus;
	}

	/**
	 * 다른 모듈의 에러코드와 type URI가 겹치지 않도록 모듈 접두사를 붙인다.
	 * 예) https://.../problems/media/upload-limit-exceeded
	 */
	@Override
	public String slug() {
		return "media/" + ErrorCode.super.slug();
	}
}
