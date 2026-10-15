package com.ok.media;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MediaUrlResolver {

	private final String baseUrl;

	public MediaUrlResolver(@Value("${app.s3.bucket}") String bucket, @Value("${app.s3.region}") String region) {
		this.baseUrl = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
	}

	// objectKey를 접근 가능한 형태의 url로 바꾼다.
	public String toUrl(String objectKey) {
		return baseUrl + objectKey;
	}

	public List<String> toUrls(List<String> objectKeys) {
		return objectKeys.stream().map(this::toUrl).toList();
	}

	// 우리 저장소의 url이면 objectKey를 꺼낸다.
	public Optional<String> toObjectKey(String url) {
		if (url == null || !url.startsWith(baseUrl)) {
			return Optional.empty();
		}
		return Optional.of(url.substring(baseUrl.length()));
	}
}
