package com.ok.media.service;

import com.ok.common.exception.CommonErrorCode;
import com.ok.common.exception.RestApiException;
import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.Media;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.repository.MediaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

	private static final String BUCKET = "test-bucket";
	private static final String STUB_URL = "https://test-bucket.s3.amazonaws.com/k";

	@Mock
	S3Client s3Client;

	@Mock
	S3Utilities s3Utilities;

	@Mock
	MediaRepository mediaRepository;

	@InjectMocks
	MediaService mediaService;

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(mediaService, "bucket", BUCKET);
	}

	// ---------- 실패 분기 ----------

	@Test
	void 빈_파일이면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
	}

	@Test
	void 지원하지_않는_content_type이면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "a.gif", "image/gif", new byte[]{1, 2, 3});

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
	}

	@Test
	void 이미지_용량이_10MB를_초과하면_CONTENT_TOO_LARGE_예외가_발생한다() {
		// given
		MultipartFile file = oversizedFile("image/png", MediaPolicy.MAX_IMAGE_SIZE_BYTES + 1);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.CONTENT_TOO_LARGE);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
	}

	@Test
	void 영상_용량이_30MB를_초과하면_CONTENT_TOO_LARGE_예외가_발생한다() {
		// given
		MultipartFile file = oversizedFile("video/mp4", MediaPolicy.MAX_VIDEO_SIZE_BYTES + 1);

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.CONTENT_TOO_LARGE);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
	}

	@Test
	void 이미지_타입이지만_실제_이미지가_아니면_BAD_REQUEST_예외가_발생한다() {
		// given
		MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes());

		// when & then
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.BAD_REQUEST);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
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
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
				.isInstanceOf(RestApiException.class)
				.extracting("errorCode").isEqualTo(CommonErrorCode.INTERNAL_ERROR);
		then(s3Client).shouldHaveNoInteractions();
		then(mediaRepository).shouldHaveNoInteractions();
	}

	// ---------- 성공 분기 ----------

	@Test
	void png_이미지를_업로드하면_jpg로_변환되어_S3에_저장되고_메타데이터가_저장된다() throws IOException {
		// given
		MockMultipartFile file = pngFile(100, 100);
		stubSuccessfulUpload();

		// when
		MediaInfo result = mediaService.upload(MediaPurpose.PRODUCT, file);

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
		MediaInfo result = mediaService.upload(MediaPurpose.PRODUCT_REVIEW, file);

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
		mediaService.upload(MediaPurpose.PRODUCT, file);

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
		assertThatThrownBy(() -> mediaService.upload(MediaPurpose.PRODUCT, file))
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
