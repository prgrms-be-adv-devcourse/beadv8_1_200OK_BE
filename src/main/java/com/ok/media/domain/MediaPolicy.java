package com.ok.media.domain;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

public class MediaPolicy {
	// 허용 용량 정책
	public static final long MAX_IMAGE_SIZE_MB = 10;
	public static final long MAX_VIDEO_SIZE_MB = 30;

	public static final long MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_SIZE_MB * 1024L * 1024L;
	public static final long MAX_VIDEO_SIZE_BYTES = MAX_VIDEO_SIZE_MB * 1024L * 1024L;

	// 저장하는 이미지의 최대 크기, 넘으면 비율을 유지해 축소한다
	public static final int MAX_STORED_IMAGE_WIDTH = 1600;
	public static final int MAX_STORED_IMAGE_HEIGHT = 1600;

	// 디코딩 허용 해상도
	public static final int MAX_DECODE_DIMENSION = 8000;
	public static final long MAX_DECODE_PIXELS = 40_000_000L;

	// 서버별 이미지 디코딩 동시 실행 정책
	// 작은 서버에서도 버티도록 한 번에 하나만 디코딩한다
	// 상품 이미지를 여러 장 동시에 올리면 곧바로 거절하지 않고 앞 요청이 끝날 때까지 기다린다
	public static final int MAX_CONCURRENT_DECODES = 1;
	public static final Duration DECODE_WAIT_TIMEOUT = Duration.ofSeconds(10);

	// 회원별 업로드 횟수 정책
	// 상품 하나에 이미지가 최대 10장이라, 상품 여러 개를 연달아 등록해도 걸리지 않을 만큼 둔다
	public static final int MAX_UPLOADS_PER_WINDOW = 50;
	public static final long UPLOAD_LIMIT_WINDOW_MINUTES = 10;
	public static final Duration UPLOAD_LIMIT_WINDOW = Duration.ofMinutes(UPLOAD_LIMIT_WINDOW_MINUTES);

	// 허용 포멧 정책
	private static final Set<String> IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png");
	private static final Set<String> VIDEO_CONTENT_TYPES = Set.of("video/mp4");

	public static boolean isAllowedImageContentType(String contentType) {
		if (contentType == null) return false;
		return IMAGE_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
	}

	public static boolean isAllowedVideoContentType(String contentType) {
		if (contentType == null) return false;
		return VIDEO_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT));
	}

	public static boolean isDecodableResolution(int width, int height) {
		if (width > MAX_DECODE_DIMENSION || height > MAX_DECODE_DIMENSION) return false;
		// int 곱셈은 오버플로가 날 수 있어 long으로 계산
		return (long) width * height <= MAX_DECODE_PIXELS;
	}

	private MediaPolicy() {
	}
}
