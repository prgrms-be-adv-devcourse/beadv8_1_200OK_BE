package com.ok.media.controller;

import com.ok.common.exception.CommonErrorCode;
import com.ok.common.exception.RestApiException;
import com.ok.common.media.dto.MediaInfo;
import com.ok.media.domain.MediaPurpose;
import com.ok.media.service.MediaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MediaController.class)
@AutoConfigureMockMvc(addFilters = false)
class MediaControllerTest {

	private static final String URL = "/api/v1/media/{purpose}";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	MediaService mediaService;

	@Test
	void 업로드_성공시_201과_MediaInfo를_반환한다() throws Exception {
		// given
		MockMultipartFile file = pngPart();
		given(mediaService.upload(eq(MediaPurpose.PRODUCT), any()))
				.willReturn(new MediaInfo(1L, "https://test/x.jpg"));

		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(file))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mediaId").value(1))
				.andExpect(jsonPath("$.url").value("https://test/x.jpg"));
	}

	@Test
	void 유효하지_않은_purpose면_400을_반환한다() throws Exception {
		// given
		MockMultipartFile file = pngPart();

		// when & then
		mockMvc.perform(multipart(URL, "NOT_A_PURPOSE").file(file))
				.andExpect(status().isBadRequest());
		then(mediaService).shouldHaveNoInteractions();
	}

	@Test
	void 서비스가_RestApiException을_던지면_ProblemDetail로_변환된다() throws Exception {
		// given
		String detail = "최대 10MB의 이미지만 업로드할 수 있습니다.";
		MockMultipartFile file = pngPart();
		given(mediaService.upload(any(), any()))
				.willThrow(new RestApiException(CommonErrorCode.CONTENT_TOO_LARGE, detail));

		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(file))
				.andExpect(status().is(413))
				.andExpect(jsonPath("$.detail").value(detail));
	}

	private static MockMultipartFile pngPart() {
		return new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
	}
}
