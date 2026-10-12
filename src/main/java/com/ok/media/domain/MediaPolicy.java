package com.ok.media.domain;

import java.util.Locale;
import java.util.Set;

public class MediaPolicy {
	// 허용 용량 정책
	public static final long MAX_IMAGE_SIZE_MB = 10;
	public static final long MAX_VIDEO_SIZE_MB = 30;

	public static final long MAX_IMAGE_SIZE_BYTES = MAX_IMAGE_SIZE_MB * 1024L * 1024L;
	public static final long MAX_VIDEO_SIZE_BYTES = MAX_VIDEO_SIZE_MB * 1024L * 1024L;

	// 이미지 최대 사이즈
	public static final int MAX_IMAGE_WIDTH = 1600;
	public static final int MAX_IMAGE_HEIGHT = 1600;

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

	private MediaPolicy() {
	}
}
