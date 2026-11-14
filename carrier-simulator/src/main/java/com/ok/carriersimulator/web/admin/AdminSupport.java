package com.ok.carriersimulator.web.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 관리 화면 컨트롤러가 함께 쓰는 입력 해석과 표시 형식.
 * 폼 값은 문자열로 받아 여기서 해석한다. 잘못된 값이 500이 아니라 안내 문구가 되게 하기 위해서다.
 */
final class AdminSupport {

    // delivery가 Delivery Tracker에 보내는 택배사 코드와 같다
    static final List<String> CARRIERS = List.of("kr.cjlogistics", "kr.epost", "kr.hanjin", "kr.lotte", "kr.logen");
    static final List<Integer> REFRESH_OPTIONS = List.of(0, 3, 5, 10);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));

    private AdminSupport() {
    }

    /**
     * 새로고침 간격을 읽는다. 고를 수 있는 값이 아니면 끔(0)이다.
     */
    static int refreshSeconds(String value) {
        try {
            int seconds = Integer.parseInt(value == null ? "" : value.strip());
            return REFRESH_OPTIONS.contains(seconds) ? seconds : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 폼을 처리한 뒤 돌아갈 주소. 고른 새로고침 간격을 유지한다.
     */
    static String redirect(String path, String refresh) {
        int seconds = refreshSeconds(refresh);
        return "redirect:" + path + (seconds > 0 ? "?refresh=" + seconds : "");
    }

    static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "에는 정수를 넣어야 합니다.");
        }
    }

    static double parseDouble(String value, String label) {
        try {
            return Double.parseDouble(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "에는 숫자를 넣어야 합니다.");
        }
    }

    static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 값이 비어 있습니다.");
        }
        return value.strip();
    }

    /**
     * 받는 곳 주소를 확인한다. 비어 있으면 null이고, 있으면 http(s) 주소여야 한다.
     */
    static String optionalHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.strip();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new IllegalArgumentException("받는 곳 주소는 http 또는 https 주소여야 합니다.");
        }
        return url;
    }

    static String time(Instant instant) {
        return instant == null ? "-" : TIME.format(instant);
    }

    static String seconds(Duration duration) {
        return duration == null ? "-" : String.format(Locale.ROOT, "%.1f초", duration.toMillis() / 1000.0);
    }
}
