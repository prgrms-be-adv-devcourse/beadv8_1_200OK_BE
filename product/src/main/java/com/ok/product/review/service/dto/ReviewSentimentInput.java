package com.ok.product.review.service.dto;

/**
 * 감성 분석기에 넘기는 리뷰 본문 하나다.
 *
 * @param choiceSeed 선택지 순서를 섞는 시드. 분석기에 보내지 않는다. 같은 시드는 늘 같은 순서를 받는다.
 *                   작업자는 리뷰 ID를, 관리자 분석 테스트는 본문 해시를 쓴다
 */
public record ReviewSentimentInput(
	long choiceSeed,
	String productName,
	String optionName,
	String content
) {

	/**
	 * 리뷰 ID가 없는 본문(관리자 분석 테스트)의 입력을 만든다. 본문 해시를 시드로 써서 같은 본문은 같은 선택지 순서를 받는다.
	 */
	public static ReviewSentimentInput seededByContent(String productName, String optionName, String content) {
		// String.hashCode는 명세로 정해진 값이라 재시작하거나 서버가 달라도 같은 본문은 같은 시드다
		return new ReviewSentimentInput(content.hashCode(), productName, optionName, content);
	}
}
