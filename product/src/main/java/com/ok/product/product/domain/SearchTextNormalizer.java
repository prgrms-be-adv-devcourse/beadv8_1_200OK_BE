package com.ok.product.product.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 상품 검색용 문자열을 저장과 검색에 같은 규칙으로 정규화한다.
 * 저장할 때와 검색할 때 규칙이 하나라도 다르면 같은 단어가 서로 다른 토큰이 되어 찾지 못하므로, 두 쪽 모두 이 클래스만 쓴다.
 */
public final class SearchTextNormalizer {

	/**
	 * 검색 행 계산 규칙의 버전이다. 이 클래스의 정규화 규칙과 {@code search_item}을 고르는 품목 규칙을 함께 가리킨다.
	 * 정규화 규칙, 품목 사전, 한 글자 품목 표 중 하나라도 바꾸면 올린다. 기동 때 백필이 이보다 낮은 버전으로 저장된 행을 다시 계산한다.
	 */
	public static final int VERSION = 2;

	private static final char FIRST_SYLLABLE = '가';
	private static final char LAST_SYLLABLE = '힣';
	private static final char FIRST_COMPATIBILITY_JAMO = 'ㄱ';
	// 초성 검색에 쓰는 ㄱ~ㅣ까지만 남긴다. 그 뒤(U+3164~U+318E)는 옛 한글 자모와 채움 문자라 검색어로 쓰이지 않는다
	private static final char LAST_COMPATIBILITY_JAMO = 'ㅣ';
	private static final int SYLLABLES_PER_CHOSEONG = 588;
	private static final int SYLLABLES_PER_JUNGSEONG = 28;

	private static final String CHOSEONG = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
	private static final String JUNGSEONG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
	// 종성 0번은 받침 없음이라 표에 넣지 않고 1번부터 담는다
	private static final String JONGSEONG = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";

	private SearchTextNormalizer() {
	}

	/**
	 * 한글·영문 소문자·숫자만 남기고 나머지는 공백 하나로 바꾼다. null이나 공백뿐이면 빈 문자열이다.
	 */
	public static String text(String value) {
		if (value == null) {
			return "";
		}
		// macOS 등 일부 입력은 한글을 NFD(자모 분리형)로 보낸다. NFC로 합치지 않으면 분리된 자모가 지워지거나
		// 저장한 값과 검색어가 서로 다른 글자가 된다
		String lower = Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
		StringBuilder builder = new StringBuilder(lower.length());
		boolean pendingSpace = false;
		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			// "·" 같은 구분 기호를 지우지 않고 공백으로 바꿔야 "김장·김치용"이 "김장 김치용" 두 단어로 나뉜다
			if (!isKept(c)) {
				pendingSpace = true;
				continue;
			}
			if (pendingSpace && !builder.isEmpty()) {
				builder.append(' ');
			}
			pendingSpace = false;
			builder.append(c);
		}
		return builder.toString();
	}

	/**
	 * {@link #text(String)} 결과에서 공백을 모두 뺀다. 띄어쓰기가 달라도 같은 값이 된다.
	 */
	public static String compact(String value) {
		return text(value).replace(" ", "");
	}

	/**
	 * {@link #compact(String)} 결과의 한글 음절을 초성·중성·종성 호환 자모로 나눈다. 다른 문자는 그대로 둔다.
	 * 겹모음(ㅘ)과 겹받침(ㄳ)은 한 글자로 둔다.
	 */
	public static String jamo(String value) {
		String compact = compact(value);
		StringBuilder builder = new StringBuilder(compact.length() * 3);
		for (int i = 0; i < compact.length(); i++) {
			char c = compact.charAt(i);
			if (c < FIRST_SYLLABLE || c > LAST_SYLLABLE) {
				builder.append(c);
				continue;
			}
			// 한 글자 오타(옆 키, 모음 혼동, 받침 누락)는 음절로 보면 글자 전체가 달라지지만 자모로 보면 한 자만 다르다.
			// 자모열에 ngram을 걸면 나머지 자모가 겹쳐 오타가 있어도 찾힌다
			int index = c - FIRST_SYLLABLE;
			builder.append(CHOSEONG.charAt(index / SYLLABLES_PER_CHOSEONG))
				.append(JUNGSEONG.charAt(index % SYLLABLES_PER_CHOSEONG / SYLLABLES_PER_JUNGSEONG));
			int jongseong = index % SYLLABLES_PER_JUNGSEONG;
			if (jongseong > 0) {
				builder.append(JONGSEONG.charAt(jongseong - 1));
			}
		}
		return builder.toString();
	}

	private static boolean isKept(char c) {
		return (c >= FIRST_SYLLABLE && c <= LAST_SYLLABLE)
			|| (c >= FIRST_COMPATIBILITY_JAMO && c <= LAST_COMPATIBILITY_JAMO)
			|| (c >= 'a' && c <= 'z')
			|| (c >= '0' && c <= '9');
	}
}
