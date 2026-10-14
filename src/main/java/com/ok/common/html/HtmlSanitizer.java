package com.ok.common.html;

import org.owasp.html.CssSchema;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;

import java.util.List;
import java.util.function.Predicate;

// 사용자가 입력한 HTML에서 허용한 태그, 속성, CSS만 남긴다 (XSS 방지용)
// 정책을 만드는 비용이 있어 생성할 때 한 번만 만들고, 만든 객체를 재사용한다 (스레드 안전)
public final class HtmlSanitizer {

	// style 속성에서 남길 CSS 속성
	private static final CssSchema ALLOWED_CSS = CssSchema.withProperties(List.of(
		"color", "background-color",
		"font-size", "font-weight", "font-style", "text-decoration",
		"text-align", "line-height",
		"width", "height"));

	// b, i, u, strong, span, p, h1~h6, ul, ol, li, blockquote, table, a href 등
	private static final PolicyFactory BASE_POLICY = Sanitizers.FORMATTING
		.and(Sanitizers.BLOCKS)
		.and(Sanitizers.TABLES)
		.and(Sanitizers.LINKS)
		.and(new HtmlPolicyBuilder()
			.allowStyling(ALLOWED_CSS)
			.allowElements("figure", "figcaption", "hr", "pre")
			.toFactory());

	private final PolicyFactory policy;

	/**
	 * 허용할 이미지 주소 조건을 받아 정제 정책을 만든다.
	 *
	 * @param imageSrcAllowed img의 src로 허용할 주소인지 판단한다. 허용하지 않으면 img 태그째 지운다
	 */
	public HtmlSanitizer(Predicate<String> imageSrcAllowed) {
		PolicyFactory imagePolicy = new HtmlPolicyBuilder()
			// src가 허용되지 않아 빠졌으면 img 태그 자체를 지운다
			.allowElements((elementName, attrs) -> hasSrc(attrs) ? elementName : null, "img")
			.allowAttributes("src")
				.matching((elementName, attributeName, value) -> imageSrcAllowed.test(value) ? value : null)
				.onElements("img")
			.allowAttributes("alt").onElements("img")
			.allowUrlProtocols("https")
			.toFactory();
		this.policy = BASE_POLICY.and(imagePolicy);
	}

	public String sanitize(String html) {
		return policy.sanitize(html);
	}

	// img에 src가 남아 있는지 확인. 값이 없으면 지울 수 있도록
	private static boolean hasSrc(List<String> attrs) {
		for (int i = 0; i < attrs.size(); i += 2) {
			if (attrs.get(i).equals("src")) {
				return true;
			}
		}
		return false;
	}
}
