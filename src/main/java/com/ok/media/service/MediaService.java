package com.ok.media.service;

import com.ok.common.exception.CommonErrorCode;
import com.ok.common.exception.RestApiException;
import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.Media;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.repository.MediaRepository;
import lombok.RequiredArgsConstructor;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {
	@Value("${app.s3.bucket}")
	private String bucket;

	private final S3Client s3Client;
	private final MediaRepository mediaRepository;

	// S3 업로드 완료 후 저장된 객체의 최종 정보 (objectKey, 최종 contentType, 최종 바이트 크기)
	private record UploadResult(String objectKey, String contentType, long size) {
	}

	// jpg 로 변환 + 압축
	public byte[] imageResizeAndCompress(MultipartFile file) throws IOException {
		BufferedImage source;
		try (InputStream inputStream = file.getInputStream()) {
			source = ImageIO.read(inputStream);
		}
		if (source == null) {
			throw new RestApiException(CommonErrorCode.BAD_REQUEST, "이미지 파일을 읽을 수 없습니다.");
		}

		Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(source);
		if (source.getWidth() > MediaPolicy.MAX_IMAGE_WIDTH || source.getHeight() > MediaPolicy.MAX_IMAGE_HEIGHT) {
			// 기준 width 혹은 height를 초과시 비율에 맞춰 리사이징
			builder.size(MediaPolicy.MAX_IMAGE_WIDTH, MediaPolicy.MAX_IMAGE_HEIGHT);
		} else {
			// size or scale 중 하나는 필수라 원본 그대로 유지하도록 scale(1) 사용함
			builder.scale(1.0);
		}

		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			builder.outputQuality(0.75)
					.outputFormat("jpg")
					.toOutputStream(outputStream);
			return outputStream.toByteArray();
		}
	}

	public MediaInfo upload(MediaPurpose purpose, MultipartFile file) {
		if (file.isEmpty()) {
			throw new RestApiException(CommonErrorCode.BAD_REQUEST, "빈 파일은 업로드할 수 없습니다.");
		}
		String contentType = file.getContentType();
		String objectKeyWithoutExtension = purpose.getDirectory() + "/" + UUID.randomUUID();

		UploadResult result;
		try {
			if (MediaPolicy.isAllowedImageContentType(contentType)) {
				if (file.getSize() > MediaPolicy.MAX_IMAGE_SIZE_BYTES) {
					throw new RestApiException(CommonErrorCode.CONTENT_TOO_LARGE, "최대 %dMB의 이미지만 업로드할 수 있습니다.".formatted(MediaPolicy.MAX_IMAGE_SIZE_MB));
				}

				result = uploadImage(file, objectKeyWithoutExtension);
			} else if (MediaPolicy.isAllowedVideoContentType(contentType)) {
				if (file.getSize() > MediaPolicy.MAX_VIDEO_SIZE_BYTES) {
					throw new RestApiException(CommonErrorCode.CONTENT_TOO_LARGE, "최대 %dMB의 영상만 업로드할 수 있습니다.".formatted(MediaPolicy.MAX_VIDEO_SIZE_MB));
				}

				result = uploadVideo(file, objectKeyWithoutExtension);
			} else {
				throw new RestApiException(CommonErrorCode.BAD_REQUEST, "해당 파일은 지원하지 않는 형식입니다.");
			}
		} catch (IOException e) {
			throw new RestApiException(CommonErrorCode.INTERNAL_ERROR, "파일 처리에 실패하였습니다.");
		}

		// S3 putObject 성공 후 메타데이터 저장
		Media media = mediaRepository.save(
				Media.of(purpose, result.objectKey(), result.contentType(), result.size())
		);

		return new MediaInfo(media.getId(), buildUrl(result.objectKey()));
	}

	private UploadResult uploadImage(MultipartFile file, String objectKeyWithoutExtension) throws IOException {
		byte[] imageBytes = imageResizeAndCompress(file); // jpg로 변환됨
		String contentType = "image/jpeg";
		String objectKey = objectKeyWithoutExtension + ".jpg";

		s3Client.putObject(
				createUploadRequest(objectKey, contentType),
				RequestBody.fromBytes(imageBytes)
		);

		// 변환 후 크기는 원본과 다르므로 변환된 바이트 길이를 저장
		return new UploadResult(objectKey, contentType, imageBytes.length);
	}

	private UploadResult uploadVideo(MultipartFile file, String objectKeyWithoutExtension) throws IOException {
		long fileSize = file.getSize();
		String contentType = file.getContentType();
		String objectKey = objectKeyWithoutExtension + ".mp4"; // mp4외 형식 지원시 수정 필요

		try (InputStream inputStream = file.getInputStream()) {
			s3Client.putObject(
					createUploadRequest(objectKey, contentType),
					RequestBody.fromInputStream(inputStream, fileSize)
			);
		}

		return new UploadResult(objectKey, contentType, fileSize);
	}

	// 이미지, 비디오 각 타입 공통 request 파라미터 빌더
	private PutObjectRequest createUploadRequest(
			String objectKey,
			String contentType
	) {
		return PutObjectRequest.builder()
				.bucket(bucket)
				.key(objectKey)
				.contentType(contentType)
				.build();
	}

	// 접근 가능한 s3 url 경로 반환
	private String buildUrl(String objectKey) {
		return s3Client.utilities()
				.getUrl(GetUrlRequest.builder().bucket(bucket).key(objectKey).build())
				.toExternalForm();
	}
}
