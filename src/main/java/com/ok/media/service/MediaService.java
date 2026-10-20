package com.ok.media.service;

import com.ok.common.exception.CommonErrorCode;
import com.ok.common.exception.RestApiException;
import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.Media;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.exception.MediaErrorCode;
import com.ok.media.repository.MediaRepository;
import com.ok.media.service.dto.MediaPurposeCode;
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
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Iterator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {
	@Value("${app.s3.bucket}")
	private String bucket;

	private final S3Client s3Client;
	private final MediaRepository mediaRepository;
	private final Clock clock;
	private final ImageDecodeLimiter imageDecodeLimiter;

	// S3 업로드 완료 후 저장된 객체의 최종 정보 (objectKey, 최종 contentType, 최종 바이트 크기)
	private record UploadResult(String objectKey, String contentType, long size) {
	}

	// jpg 로 변환 + 압축
	public byte[] imageResizeAndCompress(MultipartFile file) throws IOException {
		// 디코딩한 원본은 압축이 끝날 때까지 메모리에 남으므로 압축까지 묶어 동시 실행 수를 제한한다
		return imageDecodeLimiter.run(() -> {
			BufferedImage source = decodeWithinResolutionLimit(file);

			Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(source);
			if (source.getWidth() > MediaPolicy.MAX_STORED_IMAGE_WIDTH || source.getHeight() > MediaPolicy.MAX_STORED_IMAGE_HEIGHT) {
				// 기준 width 혹은 height를 초과시 비율에 맞춰 리사이징
				builder.size(MediaPolicy.MAX_STORED_IMAGE_WIDTH, MediaPolicy.MAX_STORED_IMAGE_HEIGHT);
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
		});
	}

	// ImageIO.read는 해상도와 상관없이 픽셀을 모두 디코딩하므로, 헤더의 가로·세로를 먼저 확인한 뒤에만 디코딩한다
	private BufferedImage decodeWithinResolutionLimit(MultipartFile file) throws IOException {
		try (InputStream inputStream = file.getInputStream();
			 ImageInputStream imageInputStream = ImageIO.createImageInputStream(inputStream)) {
			ImageReader reader = findAllowedReader(imageInputStream);
			try {
				reader.setInput(imageInputStream, true, true);
				if (!MediaPolicy.isDecodableResolution(reader.getWidth(0), reader.getHeight(0))) {
					throw new RestApiException(
							MediaErrorCode.IMAGE_RESOLUTION_EXCEEDED,
							"가로·세로 %dpx, 총 %d만 픽셀 이하의 이미지만 업로드할 수 있습니다."
									.formatted(MediaPolicy.MAX_DECODE_DIMENSION, MediaPolicy.MAX_DECODE_PIXELS / 10_000)
					);
				}
				return reader.read(0);
			} catch (IOException e) {
				// 헤더나 픽셀 데이터가 깨진 파일, 사용자가 고칠 수 있는 입력 오류라 500이 아닌 400으로 응답한다
				throw new RestApiException(MediaErrorCode.UNREADABLE_IMAGE);
			} finally {
				reader.dispose();
			}
		}
	}

	// ImageIO는 Content-Type이 아니라 바이트로 리더를 고름
	// 허용된 형식의 리더만 써서 사용자 파일을 읽는 디코더를 둘로 줄인다
	private ImageReader findAllowedReader(ImageInputStream imageInputStream) {
		Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInputStream);
		while (readers.hasNext()) {
			ImageReader reader = readers.next();
			ImageReaderSpi provider = reader.getOriginatingProvider();
			if (provider != null && provider.getMIMETypes() != null
					&& Arrays.stream(provider.getMIMETypes()).anyMatch(MediaPolicy::isAllowedImageContentType)) {
				return reader;
			}
			reader.dispose();
		}
		throw new RestApiException(MediaErrorCode.UNREADABLE_IMAGE);
	}

	public MediaInfo upload(Long memberId, MediaPurposeCode purposeCode, MultipartFile file) {
		MediaPurpose purpose = purposeCode.toDomain();

		// 한도를 넘은 요청이 이미지 디코딩·S3 업로드 자원을 쓰기 전에 끊는다
		validateUploadLimit(memberId);

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
				Media.of(memberId, purpose, result.objectKey(), result.contentType(), result.size())
		);

		return new MediaInfo(media.getId(), buildUrl(result.objectKey()));
	}

	// 저장된 미디어 수로 세므로 실패한 업로드는 횟수에 들어가지 않는다.
	// 세기와 저장이 원자적이지 않아 동시 요청이면 한도를 몇 건 넘을 수 있는데, 남용 방지가 목적이라 감수한다
	private void validateUploadLimit(Long memberId) {
		LocalDateTime windowStart = LocalDateTime.now(clock).minus(MediaPolicy.UPLOAD_LIMIT_WINDOW);
		long recentUploads = mediaRepository.countByMemberIdAndCreatedAtAfter(memberId, windowStart);
		if (recentUploads >= MediaPolicy.MAX_UPLOADS_PER_WINDOW) {
			throw new RestApiException(MediaErrorCode.UPLOAD_LIMIT_EXCEEDED);
		}
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
