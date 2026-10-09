package com.ok.media.controller.spec;

import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.domain.MediaPurpose;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Media", description = "미디어 업로드 API")
@RequestMapping("/api/v1/media")
public interface MediaApiSpec {

	@Operation(
			summary = "미디어 업로드",
			description = "용도에 맞는 이미지 또는 영상 파일을 업로드합니다. "
					+ "요청당 파일 1개를 전달하며, 성공 시 미디어 ID와 접근 URL을 반환합니다.\n\n"
					+ "- 이미지: image/jpeg, image/png, 최대 " + MediaPolicy.MAX_IMAGE_SIZE_MB + "MB. "
					+ "서버에서 jpg 로 변환되며, " + MediaPolicy.MAX_IMAGE_WIDTH + "x" + MediaPolicy.MAX_IMAGE_HEIGHT
					+ " 를 넘는 경우에만 비율을 유지한 채 그 안에 들어가도록 축소됩니다 (작은 이미지는 확대하지 않음).\n"
					+ "- 영상: video/mp4, 최대 " + MediaPolicy.MAX_VIDEO_SIZE_MB + "MB. 원본 그대로 저장됩니다.\n"
					+ "- 응답 url 은 S3 공개 주소이며 img, video 태그에 바로 사용할 수 있습니다."
	)
	@ApiResponse(responseCode = "201", description = "업로드 성공")
	@PostMapping(
			value = "/{purpose}",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE
	)
	ResponseEntity<MediaInfo> upload(
			@Parameter(
					description = "업로드 용도",
					example = "PRODUCT",
					required = true
			)
			@PathVariable("purpose") MediaPurpose purpose,

			@Parameter(description = "업로드할 파일", required = true)
			@RequestPart("file") MultipartFile file
	);
}