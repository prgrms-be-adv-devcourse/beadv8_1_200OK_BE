package com.ok.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.ok.media.domain.Media;
import com.ok.media.domain.MediaPolicy;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.repository.MediaRepository;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// 회원별 업로드 횟수 제한. 업로드 성공은 S3 Fake가 없어 미디어 행을 직접 저장해 횟수를 채운다.
class MediaUploadLimitIntegrationTest extends AbstractIntegrationTest {

	private static final String URL = "/api/v1/media/{purpose}";
	private static final Long MEMBER_ID = 1L;
	private static final Long OTHER_MEMBER_ID = 2L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	@Autowired
	MediaRepository mediaRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@AfterEach
	void tearDown() {
		mediaRepository.deleteAll();
	}

	@Test
	void 최근_업로드가_한도에_도달한_회원이_업로드하면_429이고_미디어가_늘지_않는다() throws Exception {
		// given
		saveMedia(MEMBER_ID, MediaPolicy.MAX_UPLOADS_PER_WINDOW);
		long mediaCount = mediaRepository.count();
		MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(file)
						.header(HttpHeaders.AUTHORIZATION, tokens.bearer(MEMBER_ID, "BUYER")))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.type", endsWith("/media/upload-limit-exceeded")));
		assertThat(mediaRepository.count()).isEqualTo(mediaCount);
	}

	@Test
	void 업로드_횟수는_해당_회원이_기준_시각_이후에_올린_미디어만_센다() {
		// given
		saveMedia(MEMBER_ID, 2);
		saveMedia(OTHER_MEMBER_ID, 1);
		Media latest = mediaRepository.findAll().getLast();

		// when
		long sinceBeforeUploads = mediaRepository.countByMemberIdAndCreatedAtAfter(
				MEMBER_ID, latest.getCreatedAt().minusMinutes(1));
		long sinceLatestUpload = mediaRepository.countByMemberIdAndCreatedAtAfter(
				MEMBER_ID, latest.getCreatedAt());

		// then
		assertThat(sinceBeforeUploads).isEqualTo(2);
		assertThat(sinceLatestUpload).isZero();
	}

	private void saveMedia(Long memberId, int count) {
		IntStream.range(0, count).forEach(i -> mediaRepository.save(
				Media.of(memberId, MediaPurpose.PRODUCT, "product/" + UUID.randomUUID() + ".jpg", "image/jpeg", 100L)));
	}
}
