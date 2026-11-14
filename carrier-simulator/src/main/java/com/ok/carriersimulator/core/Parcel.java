package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 송장 한 건의 상태. 시나리오의 단계를 시간이 된 만큼만 사건으로 만들어 둔다.
 */
final class Parcel {

    private final ParcelKey key;
    private final Scenario scenario;
    private final Instant virtualStart;
    // 송장마다 구간 길이를 조금씩 다르게 하는 배율. 더미 송장이 한꺼번에 같은 순간에 바뀌지 않게 한다
    private final double factor;
    // 이력에 나타나는 순서로 정렬한 단계. 시나리오가 한 번만 만들어 둔 목록을 송장이 함께 쓴다
    private final List<Scenario.Step> stepsByVisibility;
    // 언제나 사건 시각 순이다. 시각이 같으면 나타난 순서를 지킨다
    private final List<Entry> entries = new ArrayList<>();
    private int materialized;
    // 알림으로 이미 알린 사건 수
    private int notified;
    // 다음 변화 시각 순서 줄에 들어 있는지. 같은 송장이 두 번 들어가지 않게 한다
    private boolean scheduled;
    private boolean manual;
    private WebhookRegistration registration;
    private long queryCount;
    private Instant firstQueriedAt;
    private Instant lastQueriedAt;

    Parcel(ParcelKey key, Scenario scenario, Instant virtualStart, double factor) {
        this.key = key;
        this.scenario = scenario;
        this.virtualStart = virtualStart;
        this.factor = factor;
        this.stepsByVisibility = scenario.stepsByVisibility();
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
            add(new TrackingEvent(step.status(), time, step.location(), step.description()));
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
     * 시각 순으로 정렬한 사건. 돌려준 목록은 바꿀 수 없고, 잠금 밖으로 내보낼 때는 복사해야 한다.
     */
    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    StatusCode lastStatus() {
        return entries.isEmpty() ? null : entries.getLast().event.status();
    }

    /**
     * 아직 알리지 않은 사건 수를 돌려주고, 모두 알린 것으로 표시한다.
     */
    int takeUnnotified() {
        int count = entries.size() - notified;
        notified = entries.size();
        return count;
    }

    boolean scheduled() {
        return scheduled;
    }

    void scheduled(boolean scheduled) {
        this.scheduled = scheduled;
    }

    boolean hasActiveRegistration(Instant now) {
        return registration != null && registration.activeAt(now);
    }

    void queried(Instant now) {
        queryCount++;
        if (firstQueriedAt == null) {
            firstQueriedAt = now;
        }
        lastQueriedAt = now;
    }

    /**
     * 시나리오 진행을 멈추고 지정한 사건을 마지막에 덧붙인다.
     */
    void pin(TrackingEvent event) {
        manual = true;
        add(event);
    }

    ParcelView view() {
        return new ParcelView(key, scenario, manual, lastStatus(), firstQueriedAt, queryCount, lastQueriedAt);
    }

    ParcelKey key() {
        return key;
    }

    Scenario scenario() {
        return scenario;
    }

    WebhookRegistration registration() {
        return registration;
    }

    void register(WebhookRegistration registration) {
        this.registration = registration;
    }

    // 이력 끼어듦처럼 더 이른 시각의 사건이 나중에 생기면 제자리를 찾아 넣는다
    private void add(TrackingEvent event) {
        int index = entries.size();
        while (index > 0 && entries.get(index - 1).event.time().isAfter(event.time())) {
            index--;
        }
        entries.add(index, new Entry(event));
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
