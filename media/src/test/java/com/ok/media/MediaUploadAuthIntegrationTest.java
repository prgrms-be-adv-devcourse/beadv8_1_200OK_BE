package com.ok.media;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

// 미디어 업로드는 BUYER 권한(구매자·판매자)만 허용한다.
class MediaUploadAuthIntegrationTest extends AbstractIntegrationTest {

	private static final String URL = "/api/v1/media/{purpose}";
	private static final Long MEMBER_ID = 1L;

	@Autowired
	WebApplicationContext context;

	@Autowired
	TestAccessTokens tokens;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	@Test
	void 토큰_없이_업로드하면_401이다() throws Exception {
		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(imageFile()))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(jsonPath("$.type", endsWith("/unauthorized")));
	}

	@Test
	void 관리자_계정으로_업로드하면_403이다() throws Exception {
		// given
		String adminToken = tokens.bearer(MEMBER_ID, "ADMIN");

		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(imageFile())
						.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type", endsWith("/forbidden")));
	}

	// 테스트용 S3 Fake가 없어 업로드 성공(201)까지는 보지 않는다.
	// 인가를 통과해야 서비스의 첫 검증(빈 파일)에 도달하므로, 400이 나면 인가를 통과한 것이다
	@ParameterizedTest
	@MethodSource("업로드_가능한_권한")
	void BUYER_권한이_있으면_인가를_통과한다(String[] roles) throws Exception {
		// given
		String token = tokens.bearer(MEMBER_ID, roles);
		MockMultipartFile emptyFile = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);

		// when & then
		mockMvc.perform(multipart(URL, "PRODUCT").file(emptyFile)
						.header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isBadRequest());
	}

	static Stream<Arguments> 업로드_가능한_권한() {
		return Stream.of(
				Arguments.of((Object) new String[]{"BUYER"}),
				Arguments.of((Object) new String[]{"BUYER", "SELLER"})
		);
	}

	private static MockMultipartFile imageFile() {
		return new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});
	}
}
