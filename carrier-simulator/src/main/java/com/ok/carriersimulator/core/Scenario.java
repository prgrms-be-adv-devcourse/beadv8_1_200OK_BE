package com.ok.carriersimulator.core;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 송장이 어떤 흐름으로 진행할지 정한 각본이다. 시간은 가상 시간으로 적고, 실제로 얼마나 걸릴지는 재생 속도가 정한다.
 * 송장 번호 앞자리로 고르므로 같은 번호는 언제나 같은 각본을 탄다.
 */
public enum Scenario {
    NORMAL("10", "정상", Duration.ZERO, false, normalSteps(1, 0)),
    SLOW("20", "느린 진행", Duration.ZERO, false, normalSteps(3, 0)),
    STUCK("30", "이동 멈춤", Duration.ZERO, false, List.of(
            step(0, StatusCode.INFORMATION_RECEIVED, null, "보내는 분이 접수했습니다"),
            step(4, StatusCode.AT_PICKUP, "산지 집화점", "집화했습니다"),
            step(10, StatusCode.IN_TRANSIT, "옥천HUB", "간선 상차했습니다"))),
    NOT_FOUND("40", "없는 송장", Duration.ZERO, true, List.of()),
    LATE_REGISTER("50", "늦게 등록", Duration.ofHours(6), false, normalSteps(1, 6)),
    REDELIVERY("60", "배송 실패 후 재배송", Duration.ZERO, false, List.of(
            step(0, StatusCode.INFORMATION_RECEIVED, null, "보내는 분이 접수했습니다"),
            step(4, StatusCode.AT_PICKUP, "산지 집화점", "집화했습니다"),
            step(10, StatusCode.IN_TRANSIT, "옥천HUB", "간선 상차했습니다"),
            step(20, StatusCode.IN_TRANSIT, "배송지 대리점", "간선 하차했습니다"),
            step(26, StatusCode.OUT_FOR_DELIVERY, "배송지 대리점", "배송을 출발했습니다"),
            step(30, StatusCode.ATTEMPT_FAIL, "배송지 대리점", "받는 분이 없어 배송하지 못했습니다"),
            step(50, StatusCode.OUT_FOR_DELIVERY, "배송지 대리점", "다시 배송을 출발했습니다"),
            step(54, StatusCode.DELIVERED, "배송지 대리점", "배송을 완료했습니다"))),
    // 10시간째 사건이 22시간째에야 이력에 나타난다. 마지막 상태는 그대로인데 이력이 바뀌는 경우를 만든다
    LATE_INSERT("70", "이력 끼어듦", Duration.ZERO, false, List.of(
            step(0, StatusCode.INFORMATION_RECEIVED, null, "보내는 분이 접수했습니다"),
            step(4, StatusCode.AT_PICKUP, "산지 집화점", "집화했습니다"),
            new Step(Duration.ofHours(10), Duration.ofHours(22), StatusCode.IN_TRANSIT, "옥천HUB", "간선 상차했습니다"),
            step(20, StatusCode.IN_TRANSIT, "배송지 대리점", "간선 하차했습니다"),
            step(26, StatusCode.OUT_FOR_DELIVERY, "배송지 대리점", "배송을 출발했습니다"),
            step(30, StatusCode.DELIVERED, "배송지 대리점", "배송을 완료했습니다"))),
    EXCEPTION("80", "사고", Duration.ZERO, false, List.of(
            step(0, StatusCode.INFORMATION_RECEIVED, null, "보내는 분이 접수했습니다"),
            step(4, StatusCode.AT_PICKUP, "산지 집화점", "집화했습니다"),
            step(10, StatusCode.IN_TRANSIT, "옥천HUB", "간선 상차했습니다"),
            step(14, StatusCode.EXCEPTION, "옥천HUB", "상품이 파손되어 배송을 중단했습니다")));

    // 90 + 시나리오 2자리 + 순번 8자리. delivery의 송장 번호 규칙(숫자 10~13자리)에 맞는다
    private static final Pattern RULE = Pattern.compile("^90(\\d{2})\\d{8}$");

    private final String code;
    private final String displayName;
    private final Duration notFoundFor;
    private final boolean alwaysNotFound;
    private final List<Step> steps;

    Scenario(String code, String displayName, Duration notFoundFor, boolean alwaysNotFound, List<Step> steps) {
        this.code = code;
        this.displayName = displayName;
        this.notFoundFor = notFoundFor;
        this.alwaysNotFound = alwaysNotFound;
        this.steps = steps;
    }

    /**
     * 송장 번호로 시나리오를 고른다. 규칙에 맞지 않는 번호(판매자가 넣은 실제 번호 포함)는 정상 시나리오다.
     */
    public static Scenario of(String trackingNumber) {
        if (trackingNumber == null) {
            return NORMAL;
        }
        Matcher matcher = RULE.matcher(trackingNumber);
        if (!matcher.matches()) {
            return NORMAL;
        }
        String code = matcher.group(1);
        return Arrays.stream(values())
                .filter(scenario -> scenario.code.equals(code))
                .findFirst()
                .orElse(NORMAL);
    }

    /**
     * 이 시나리오로 읽히는 송장 번호를 만든다.
     *
     * @param sequence 0 이상 99,999,999 이하의 순번
     */
    public String trackingNumber(long sequence) {
        if (sequence < 0 || sequence > 99_999_999L) {
            throw new IllegalArgumentException("순번은 0 이상 99,999,999 이하여야 합니다.");
        }
        return "90" + code + String.format("%08d", sequence);
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public List<Step> steps() {
        return steps;
    }

    /**
     * 언제 조회해도 없는 송장인지.
     */
    public boolean alwaysNotFound() {
        return alwaysNotFound;
    }

    /**
     * 시작한 뒤 이 가상 시간 동안은 없는 송장으로 답한다.
     */
    public Duration notFoundFor() {
        return notFoundFor;
    }

    private static List<Step> normalSteps(int slowdown, int shiftHours) {
        return List.of(
                step(shiftHours, StatusCode.INFORMATION_RECEIVED, null, "보내는 분이 접수했습니다"),
                step(shiftHours + 4 * slowdown, StatusCode.AT_PICKUP, "산지 집화점", "집화했습니다"),
                step(shiftHours + 10 * slowdown, StatusCode.IN_TRANSIT, "옥천HUB", "간선 상차했습니다"),
                step(shiftHours + 20 * slowdown, StatusCode.IN_TRANSIT, "배송지 대리점", "간선 하차했습니다"),
                step(shiftHours + 26 * slowdown, StatusCode.OUT_FOR_DELIVERY, "배송지 대리점", "배송을 출발했습니다"),
                step(shiftHours + 30 * slowdown, StatusCode.DELIVERED, "배송지 대리점", "배송을 완료했습니다"));
    }

    private static Step step(int hours, StatusCode status, String location, String description) {
        return new Step(Duration.ofHours(hours), Duration.ofHours(hours), status, location, description);
    }

    /**
     * 시나리오의 한 단계.
     *
     * @param occursAt  시작 뒤 사건이 일어나는 가상 시간. 사건의 시각이 된다
     * @param visibleAt 시작 뒤 사건이 이력에 나타나는 가상 시간. 보통 {@code occursAt}과 같다
     */
    public record Step(Duration occursAt, Duration visibleAt, StatusCode status, String location, String description) {
    }
}
