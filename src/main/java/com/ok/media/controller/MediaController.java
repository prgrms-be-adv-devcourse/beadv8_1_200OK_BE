package com.ok.media.controller;

import com.ok.common.media.dto.MediaInfo;
import com.ok.media.controller.spec.MediaApiSpec;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class MediaController implements MediaApiSpec {
	private final MediaService mediaService;

	@Override
	public ResponseEntity<MediaInfo> upload(MediaPurpose purpose, MultipartFile file) {
		MediaInfo response = mediaService.upload(purpose, file);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
