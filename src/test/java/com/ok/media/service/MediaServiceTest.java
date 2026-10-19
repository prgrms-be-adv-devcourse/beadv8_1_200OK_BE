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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Utilities;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

	private static final String BUCKET = "test-bucket";
	private static final String STUB_URL = "https://test-bucket.s3.amazonaws.com/k";
	private static final Long MEMBER_ID = 1L;
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZONE);

	@Mock
	S3Client s3Client;

	@Mock
	S3Utilities s3Utilities;

	@Mock
	MediaRepository mediaRepository;

	MediaService mediaService;

	@BeforeEach
	void setUp() {
		mediaService = new MediaService(s3Client, mediaRepository, CLOCK);
		ReflectionTestUtils.setField(mediaService, "bucket", BUCKET);
	}

	// ---------- 실패 분기 ----------

	@Test
	void 빈_파일이면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 지원하지_않는_content_type이면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "a.gif", "image/gif", new byte[]{1, 2, 3});

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 이미지_용량이_10MB를_초과하면_CONTENT_TOO_LARGE_예외가_발생한다() {
		// given
		MultipartFile file = oversizedFile("image/png", MediaPolicy.MAX_IMAGE_SIZE_BYTES + 1);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.CONTENT_TOO_LARGE);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 영상_용량이_30MB를_초과하면_CONTENT_TOO_LARGE_예외가_발생한다() {
		// given
		MultipartFile file = oversizedFile("video/mp4", MediaPolicy.MAX_VIDEO_SIZE_BYTES + 1);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.CONTENT_TOO_LARGE);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 이미지_타입이지만_실제_이미지가_아니면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes());

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 헤더의_해상도가_한도를_넘으면_디코딩하지_않고_CONTENT_TOO_LARGE_예외가_발생한다() throws IOException {
		// given
		// 실제 픽셀은 1x1이라 디코딩을 시도하면 IIOException(INTERNAL_ERROR)이 난다. CONTENT_TOO_LARGE면 디코딩 전에 거절된 것이다
		MockMultipartFile file = pngWithHeaderResolution(50_000, 50_000);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOfSatisfying(RestApiException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.CONTENT_TOO_LARGE));
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 파일_읽기에_실패하면_INTERNAL_ERROR_예외가_발생한다() throws IOException {
		// given
		MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
		given(file.isEmpty()).willReturn(false);
		given(file.getContentType()).willReturn("video/mp4");
		given(file.getSize()).willReturn(10L);
		given(file.getInputStream()).willThrow(new IOException("read failed"));

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.INTERNAL_ERROR);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 최근_10분_업로드가_50회에_도달하면_UPLOAD_LIMIT_EXCEEDED_예외가_발생하고_S3에_올리지_않는다() throws IOException {
		// given
		MockMultipartFile file = pngFile(10, 10);
		given(mediaRepository.countByMemberIdAndCreatedAtAfter(eq(MEMBER_ID), any(LocalDateTime.class)))
				.willReturn((long) MediaPolicy.MAX_UPLOADS_PER_WINDOW);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOfSatisfying(RestApiException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(MediaErrorCode.UPLOAD_LIMIT_EXCEEDED));
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).should(never()).save(any());
	}

	@Test
	void 업로드_횟수는_현재_시각부터_10분_전_이후에_올린_것으로_센다() throws IOException {
		// given
		MockMultipartFile file = pngFile(10, 10);
		given(mediaRepository.countByMemberIdAndCreatedAtAfter(eq(MEMBER_ID), any(LocalDateTime.class)))
				.willReturn((long) MediaPolicy.MAX_UPLOADS_PER_WINDOW - 1);
		stubSuccessfulUpload();

		// when
		mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file);

		// then
		ArgumentCaptor<LocalDateTime> windowStartCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		then(mediaRepository).should().countByMemberIdAndCreatedAtAfter(eq(MEMBER_ID), windowStartCaptor.capture());
		assertThat(windowStartCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 19, 11, 50));
		then(mediaRepository).should().save(any(Media.class));
	}

	// ---------- 성공 분기 ----------

	@Test
	void png_이미지를_업로드하면_jpg로_변환되어_S3에_저장되고_메타데이터가_저장된다() throws IOException {
		// given
		MockMultipartFile file = pngFile(100, 100);
		stubSuccessfulUpload();

		// when
		MediaInfo result = mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file);

		// then
		ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		then(s3Client).should().putObject(requestCaptor.capture(), any(RequestBody.class));
		PutObjectRequest request = requestCaptor.getValue();
		assertThat(request.bucket()).isEqualTo(BUCKET);
		assertThat(request.key()).matches("^product/[0-9a-f-]{36}\\.jpg$");
		assertThat(request.contentType()).isEqualTo("image/jpeg");

		ArgumentCaptor<Media> mediaCaptor = ArgumentCaptor.forClass(Media.class);
		then(mediaRepository).should().save(mediaCaptor.capture());
		Media saved = mediaCaptor.getValue();
		assertThat(saved.getMemberId()).isEqualTo(MEMBER_ID);
		assertThat(saved.getPurpose()).isEqualTo(MediaPurpose.PRODUCT);
		assertThat(saved.getObjectKey()).isEqualTo(request.key());
		assertThat(saved.getContentType()).isEqualTo("image/jpeg");
		assertThat(saved.getFileSize()).isPositive();

		assertThat(result.mediaId()).isEqualTo(1L);
		assertThat(result.url()).isEqualTo(STUB_URL);
	}

	@Test
	void mp4_영상을_업로드하면_원본_그대로_S3에_저장된다() {
		// given
		int size = 1024;
		MockMultipartFile file = mp4File(size);
		stubSuccessfulUpload();

		// when
		MediaInfo result = mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT_REVIEW, file);

		// then
		ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
		then(s3Client).should().putObject(requestCaptor.capture(), any(RequestBody.class));
		PutObjectRequest request = requestCaptor.getValue();
		assertThat(request.key()).matches("^product_review/[0-9a-f-]{36}\\.mp4$");
		assertThat(request.contentType()).isEqualTo("video/mp4");

		ArgumentCaptor<Media> mediaCaptor = ArgumentCaptor.forClass(Media.class);
		then(mediaRepository).should().save(mediaCaptor.capture());
		Media saved = mediaCaptor.getValue();
		assertThat(saved.getPurpose()).isEqualTo(MediaPurpose.PRODUCT_REVIEW);
		assertThat(saved.getContentType()).isEqualTo("video/mp4");
		assertThat(saved.getFileSize()).isEqualTo(size);

		assertThat(result.mediaId()).isEqualTo(1L);
		assertThat(result.url()).isEqualTo(STUB_URL);
	}

	@Test
	void 가로가_1600을_초과하면_비율을_유지하며_축소되어_S3에_저장된다() throws IOException {
		// given
		MockMultipartFile file = pngFile(3200, 1600);
		stubSuccessfulUpload();

		// when
		mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file);

		// then
		ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
		then(s3Client).should().putObject(any(PutObjectRequest.class), bodyCaptor.capture());
		BufferedImage uploaded;
		try (InputStream in = bodyCaptor.getValue().contentStreamProvider().newStream()) {
			uploaded = ImageIO.read(in);
		}
		assertThat(uploaded.getWidth()).isEqualTo(MediaPolicy.MAX_IMAGE_WIDTH);
		assertThat(uploaded.getHeight()).isEqualTo(800);
	}

	@Test
	void S3_업로드가_실패하면_메타데이터를_저장하지_않는다() throws IOException {
		// given
		MockMultipartFile file = pngFile(10, 10);
		given(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
				.willThrow(S3Exception.builder().message("boom").build());

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MEMBER_ID, MediaPurposeCode.PRODUCT, file))
				.isInstanceOf(S3Exception.class);
		then(mediaRepository).should(never()).save(any());
	}

	// ---------- helpers ----------

	private void stubSuccessfulUpload() {
		try {
			given(s3Client.utilities()).willReturn(s3Utilities);
			given(s3Utilities.getUrl(any(GetUrlRequest.class))).willReturn(URI.create(STUB_URL).toURL());
		} catch (java.net.MalformedURLException e) {
			throw new IllegalStateException(e);
		}
		given(mediaRepository.save(any(Media.class))).willAnswer(invocation -> {
			Media media = invocation.getArgument(0);
			ReflectionTestUtils.setField(media, "id", 1L);
			return media;
		});
	}

	private static MockMultipartFile pngFile(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			ImageIO.write(image, "png", out);
			return new MockMultipartFile("file", "a.png", "image/png", out.toByteArray());
		}
	}

	// 1x1 PNG의 IHDR 가로·세로만 바꿔, 작은 파일로 큰 해상도를 선언하는 이미지(decompression bomb)를 흉내 낸다
	private static MockMultipartFile pngWithHeaderResolution(int width, int height) throws IOException {
		byte[] png = pngFile(1, 1).getBytes();
		// PNG 시그니처(8) + IHDR 길이(4) + 타입(4) 뒤에 가로(4)·세로(4)가 온다
		ByteBuffer buffer = ByteBuffer.wrap(png);
		buffer.putInt(16, width);
		buffer.putInt(20, height);
		// 리더가 CRC를 검사해도 통과하도록 IHDR 타입과 데이터(13바이트)로 CRC를 다시 계산한다
		CRC32 crc = new CRC32();
		crc.update(png, 12, 4 + 13);
		buffer.putInt(29, (int) crc.getValue());
		return new MockMultipartFile("file", "bomb.png", "image/png", png);
	}

	private static MockMultipartFile mp4File(int size) {
		return new MockMultipartFile("file", "a.mp4", "video/mp4", new byte[size]);
	}

	// 실제 10MB+ 바이트를 만들지 않고 메타데이터만 스텁한 파일
	private static MultipartFile oversizedFile(String contentType, long size) {
		MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
		given(file.isEmpty()).willReturn(false);
		given(file.getContentType()).willReturn(contentType);
		given(file.getSize()).willReturn(size);
		return file;
	}
}
