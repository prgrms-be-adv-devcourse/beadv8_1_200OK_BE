package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 송장 한 건의 상태. 시나리오의 단계를 시간이 된 만큼만 사건으로 만들어 둔다.
 */
final class Parcel {

    private final ParcelKey key;
    private final Scenario scenario;
    private final Instant createdAt;
    private final Instant virtualStart;
    // 송장마다 구간 길이를 조금씩 다르게 하는 배율. 더미 송장이 한꺼번에 같은 순간에 바뀌지 않게 한다
    private final double factor;
    // 이력에 나타나는 순서로 정렬한 단계. 이력 끼어듦 시나리오는 일어난 순서와 나타나는 순서가 다르다
    private final List<Scenario.Step> stepsByVisibility;
    private final List<Entry> entries = new ArrayList<>();
    private int materialized;
    private boolean manual;
    private WebhookRegistration registration;
    private long queryCount;
    private Instant lastQueriedAt;

    Parcel(ParcelKey key, Scenario scenario, Instant createdAt, Instant virtualStart, double factor) {
        this.key = key;
        this.scenario = scenario;
        this.createdAt = createdAt;
        this.virtualStart = virtualStart;
        this.factor = factor;
        this.stepsByVisibility = scenario.steps().stream()
                .sorted(Comparator.comparing(Scenario.Step::visibleAt))
                .toList();
    }

    /**
     * 지금까지 보여야 할 단계를 사건으로 만든다. 새로 만든 사건 수를 돌려준다.
     */
    int materialize(PlaybackClock playback, Instant now) {
        if (manual) {
            return 0;
        }
        Instant virtualNow = playback.virtualNow(now);
        int created = 0;
        while (materialized < stepsByVisibility.size()) {
            Scenario.Step step = stepsByVisibility.get(materialized);
            if (virtualStart.plus(scale(step.visibleAt())).isAfter(virtualNow)) {
                break;
            }
            Instant time = playback.realTimeOf(virtualStart.plus(scale(step.occursAt())), now);
            entries.add(new Entry(new TrackingEvent(step.status(), time, step.location(), step.description())));
            materialized++;
            created++;
        }
        return created;
    }

    /**
     * 다음 사건이 이력에 나타나는 가상 시각. 더 나타날 사건이 없으면 null이다.
     */
    Instant nextVisibleVirtualTime() {
        if (manual || materialized >= stepsByVisibility.size()) {
            return null;
        }
        return virtualStart.plus(scale(stepsByVisibility.get(materialized).visibleAt()));
    }

    /**
     * 시각 순으로 정렬한 사건. 시각이 같으면 나타난 순서를 지킨다.
     */
    List<Entry> entries() {
        return entries.stream()
                .sorted(Comparator.comparing(entry -> entry.event.time()))
                .toList();
    }

    StatusCode lastStatus() {
        List<Entry> sorted = entries();
        return sorted.isEmpty() ? null : sorted.getLast().event.status();
    }

    void queried(Instant now) {
        queryCount++;
        lastQueriedAt = now;
    }

    /**
     * 시나리오 진행을 멈추고 지정한 사건을 마지막에 덧붙인다.
     */
    void pin(TrackingEvent event) {
        manual = true;
        entries.add(new Entry(event));
    }

    ParcelView view() {
        return new ParcelView(key, scenario, manual, lastStatus(), createdAt, queryCount, lastQueriedAt);
    }

    ParcelKey key() {
        return key;
    }

    Scenario scenario() {
        return scenario;
    }

    int entryCount() {
        return entries.size();
    }

    WebhookRegistration registration() {
        return registration;
    }

    void register(WebhookRegistration registration) {
        this.registration = registration;
    }

    private Duration scale(Duration duration) {
        return Duration.ofNanos(Math.round(duration.toNanos() * factor));
    }

    /**
     * 사건과, 그 사건이 처음으로 조회 응답에 실려 나간 시각.
     */
    static final class Entry {

        final TrackingEvent event;
        Instant firstFetchedAt;

        Entry(TrackingEvent event) {
            this.event = event;
        }
    }
}
