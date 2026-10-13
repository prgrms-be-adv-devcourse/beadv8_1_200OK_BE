package com.ok.common.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlSanitizerTest {

	private static final String BUCKET = "https://bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final Predicate<String> OUR_BUCKET = src -> src.startsWith(BUCKET);

	@Test
	void script_태그는_제거된다() {
		String result = HtmlSanitizer.sanitize("<p>설명</p><script>alert(1)</script>", OUR_BUCKET);

		assertThat(result).isEqualTo("<p>설명</p>");
	}

	@Test
	void 이벤트_속성은_제거된다() {
		String result = HtmlSanitizer.sanitize(
			"<p onclick=\"alert(1)\">설명</p><img src=\"" + BUCKET + "a.jpg\" onerror=\"alert(1)\" />", OUR_BUCKET);

		assertThat(result).doesNotContain("onclick", "onerror");
		assertThat(result).contains("<p>설명</p>", BUCKET + "a.jpg");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"<p>설명</p><iframe src=\"https://evil.com\"></iframe>",
		"<p>설명</p><style>body{display:none}</style>",
		"<p>설명</p><svg onload=\"alert(1)\"></svg>",
		"<p>설명</p><object data=\"https://evil.com/x\"></object>",
		"<p>설명</p><form action=\"https://evil.com\"><input name=\"pw\"></form>",
		"<p>설명</p><meta http-equiv=\"refresh\" content=\"0;url=https://evil.com\">"
	})
	void 허용하지_않은_위험한_태그는_내용까지_제거된다(String html) {
		assertThat(HtmlSanitizer.sanitize(html, OUR_BUCKET)).isEqualTo("<p>설명</p>");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"javascript:alert(1)",
		"JaVaScRiPt:alert(1)",
		"jav&#x09;ascript:alert(1)",
		"data:text/html,x"
	})
	void https가_아닌_링크는_글자만_남긴다(String href) {
		String result = HtmlSanitizer.sanitize("<a href=\"" + href + "\">클릭</a>", OUR_BUCKET);

		assertThat(result).isEqualTo("클릭");
	}

	@Test
	void https_링크는_남고_nofollow가_붙는다() {
		String result = HtmlSanitizer.sanitize("<a href=\"https://example.com/a\">링크</a>", OUR_BUCKET);

		assertThat(result).isEqualTo("<a href=\"https://example.com/a\" rel=\"nofollow\">링크</a>");
	}

	@Test
	void 허용한_서식_태그는_남는다() {
		String html = "<p><b>굵게</b> <i>기울임</i></p><ul><li>항목</li></ul>";

		assertThat(HtmlSanitizer.sanitize(html, OUR_BUCKET)).isEqualTo(html);
	}

	@Test
	void 에디터가_쓰는_표_그림_구분선_코드_제목_인용은_남는다() {
		String html = "<table><thead><tr><th>구분</th></tr></thead><tbody><tr><td>3kg</td></tr></tbody></table>"
			+ "<figure><img src=\"" + BUCKET + "a.jpg\" alt=\"사과\" /><figcaption>설명</figcaption></figure>"
			+ "<hr /><pre>코드</pre><h2>제목</h2><blockquote>인용</blockquote>";

		assertThat(HtmlSanitizer.sanitize(html, OUR_BUCKET)).isEqualTo(html);
	}

	@Test
	void 이미지_크기_지정은_남는다() {
		String html = "<img src=\"" + BUCKET + "a.jpg\" style=\"width:300px;height:200px\" />";

		assertThat(HtmlSanitizer.sanitize(html, OUR_BUCKET)).isEqualTo(html);
	}

	@Test
	void 에디터가_넣는_글자색_정렬_크기는_남는다() {
		String result = HtmlSanitizer.sanitize(
			"<p style=\"text-align:center\"><span style=\"color:#ff0000;font-size:20px\">빨간 글씨</span></p>",
			OUR_BUCKET);

		assertThat(result).contains("text-align:center", "color:#ff0000", "font-size:20px", "빨간 글씨");
	}

	@Test
	void 허용하지_않은_CSS는_제거된다() {
		String result = HtmlSanitizer.sanitize(
			"<p style=\"color:red;position:fixed;background-image:url(https://evil.com/x.png)\">설명</p>",
			OUR_BUCKET);

		assertThat(result).contains("color:red");
		assertThat(result).doesNotContain("position", "evil.com", "url(");
	}

	@Test
	void 허용하지_않은_이미지는_태그째_제거된다() {
		String result = HtmlSanitizer.sanitize(
			"<p>설명</p><img src=\"https://evil.com/a.jpg\" alt=\"외부\" /><img src=\"" + BUCKET + "b.jpg\" alt=\"사과\" />",
			OUR_BUCKET);

		assertThat(result).doesNotContain("evil.com", "외부");
		assertThat(result).contains("src=\"" + BUCKET + "b.jpg\"", "alt=\"사과\"");
	}
}
