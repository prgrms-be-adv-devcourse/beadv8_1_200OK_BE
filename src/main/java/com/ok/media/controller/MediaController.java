package com.ok.media.controller;

import com.ok.common.media.dto.MediaInfo;
import com.ok.media.controller.spec.MediaApiSpec;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController implements MediaApiSpec {
	private final MediaService mediaService;

	@Override
	@PostMapping(
			value = "/{purpose}",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE
	)
	public ResponseEntity<MediaInfo> upload(
			@PathVariable("purpose") MediaPurpose purpose,
			@RequestPart("file") MultipartFile file
	) {
		MediaInfo response = mediaService.upload(purpose, file);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
