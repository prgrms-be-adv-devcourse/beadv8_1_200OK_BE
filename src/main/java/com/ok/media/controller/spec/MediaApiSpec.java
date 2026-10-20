package com.ok.media.controller.spec;

import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.service.dto.MediaPurposeCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Media", description = "미디어 업로드 API")
public interface MediaApiSpec {

	@Operation(
			summary = "미디어 업로드",
			description = "용도에 맞는 이미지 또는 영상 파일을 업로드합니다. "
					+ "요청당 파일 1개를 전달하며, 성공 시 미디어 ID와 접근 URL을 반환합니다. "
					+ "구매자·판매자로 로그인해야 하며, 현재 관리자 계정은 업로드할 수 없습니다. "
					+ "회원당 최근 " + MediaPolicy.UPLOAD_LIMIT_WINDOW_MINUTES + "분 동안 "
					+ MediaPolicy.MAX_UPLOADS_PER_WINDOW + "회까지 업로드할 수 있습니다.\n\n"
					+ "- 이미지: image/jpeg, image/png, 최대 " + MediaPolicy.MAX_IMAGE_SIZE_MB + "MB. "
					+ "서버에서 jpg 로 변환되며, " + MediaPolicy.MAX_STORED_IMAGE_WIDTH + "x" + MediaPolicy.MAX_STORED_IMAGE_HEIGHT
					+ " 를 넘는 경우에만 비율을 유지한 채 그 안에 들어가도록 축소됩니다 (작은 이미지는 확대하지 않음). "
					+ "원본 해상도는 가로·세로 각 " + MediaPolicy.MAX_DECODE_DIMENSION + "px, 총 "
					+ (MediaPolicy.MAX_DECODE_PIXELS / 10_000) + "만 픽셀 이하여야 하며, 넘으면 413을 반환합니다.\n"
					+ "- 영상: video/mp4, 최대 " + MediaPolicy.MAX_VIDEO_SIZE_MB + "MB. 원본 그대로 저장됩니다.\n"
					+ "- 응답 url 은 S3 공개 주소이며 img, video 태그에 바로 사용할 수 있습니다."
	)
	@ApiResponse(responseCode = "201", description = "업로드 성공")
	@ApiResponse(responseCode = "400", description = "빈 파일, 지원하지 않는 형식, 읽을 수 없는 이미지")
	@ApiResponse(responseCode = "401", description = "로그인 필요")
	@ApiResponse(responseCode = "403", description = "구매자·판매자가 아님 (관리자 계정)")
	@ApiResponse(responseCode = "413", description = "용량 또는 해상도 초과")
	@ApiResponse(responseCode = "429", description = "업로드 횟수 초과")
	@ApiResponse(responseCode = "503", description = "이미지 처리 요청이 몰려 대기 시간 초과 (잠시 후 재시도)")
	ResponseEntity<MediaInfo> upload(
			Long memberId,

			@Parameter(
					description = "업로드 용도",
					example = "PRODUCT",
					required = true
			)
			MediaPurposeCode purpose,

			@Parameter(description = "업로드할 파일", required = true)
			MultipartFile file
	);
}
