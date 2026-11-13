package com.ok.carriersimulator.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * 가짜 택배사의 본체다. 송장을 만들고, 시간이 지난 만큼 상태를 진행시키고, 조회와 알림 등록에 답한다.
 *
 * <p>현재 시각을 스스로 읽지 않고 인자로 받는다. 같은 설정에 같은 순서로 같은 시각을 넣으면 같은 결과가 나오므로,
 * 서버 없이 시험에서 직접 불러 실패를 재현할 수 있다. 모든 공개 메서드는 한 잠금으로 직렬화한다.
 */
public final class CarrierSimulator {

    private static final int MAX_LATENCY_SAMPLES = 100_000;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_CALLBACK_RECORDS = 10_000;
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
    private static final String NOT_FOUND_LABEL = "없는 송장";

    private final SimulatorSettings settings;
    private final PlaybackClock playback;
    private final TokenRegistry tokens;
    private final StatsRecorder stats = new StatsRecorder();
    private final Map<ParcelKey, Parcel> parcels = new HashMap<>();
    // 만든 순서. 거르지 않는 목록 조회가 전체를 훑지 않고 쪽 위치로 바로 가게 한다
    private final List<Parcel> parcelOrder = new ArrayList<>();
    private final Deque<Long> detectionNanos = new ArrayDeque<>();
    private final Random faultRandom;
    private final Random shuffleRandom;
    private final CallbackQueue callbackQueue = new CallbackQueue();
    private final Deque<CallbackRecord> callbackRecords = new ArrayDeque<>();
    // 알림 등록이 있는 송장을 "다음 사건이 보이는 가상 시각" 순으로 둔다. 송장을 전부 훑지 않고 때가 된 것만 꺼낸다
    private final PriorityQueue<Scheduled> schedule = new PriorityQueue<>(
            Comparator.comparing(Scheduled::virtualTime).thenComparingLong(Scheduled::sequence));
    private long nextScheduleSequence;
    private long nextCallbackId = 1;
    private Instant lastDispatchAt;
    private DispatchSettings dispatch;
    private FaultSettings faults = FaultSettings.none();

    public CarrierSimulator(SimulatorSettings settings, Instant now) {
        this.settings = settings;
        this.playback = new PlaybackClock(now, settings.secondsPerDay());
        this.tokens = new TokenRegistry(settings.tokenTtl());
        this.faultRandom = new Random(settings.seed());
        this.shuffleRandom = new Random(settings.seed() + 1);
        this.dispatch = settings.dispatch();
    }

    /**
     * 송장을 조회한다. 처음 보는 번호면 그때 송장을 만들고 흐름을 시작한다.
     * 지금의 delivery처럼 알림 등록 없이 조회만 하는 쪽도 흐름을 볼 수 있게 하기 위해서다.
     */
    public synchronized TrackResult track(ParcelKey key, Instant now) {
        Parcel parcel = findOrCreate(key, now);
        parcel.materialize(playback, now);
        parcel.queried(now);
        List<Parcel.Entry> entries = parcel.entries();
        if (entries.isEmpty()) {
            stats.record(Metric.TRACK_NOT_FOUND, now);
            return new TrackResult.NotFound();
        }
        for (Parcel.Entry entry : entries) {
            if (entry.firstFetchedAt == null) {
                entry.firstFetchedAt = now;
                addDetection(Duration.between(entry.event.time(), now));
            }
        }
        stats.record(Metric.TRACK_OK, now);
        return new TrackResult.Found(entries.stream().map(entry -> entry.event).toList());
    }

    /**
     * 송장에 알림을 등록한다. 만료 시각이 지금이거나 과거면 등록을 지운다(Delivery Tracker와 같은 규칙).
     */
    public synchronized void registerWebhook(ParcelKey key, String callbackUrl, Instant expirationTime, Instant now) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new IllegalArgumentException("알림 받을 주소는 필수입니다.");
        }
        if (expirationTime == null) {
            throw new IllegalArgumentException("만료 시각은 필수입니다.");
        }
        stats.record(Metric.REGISTER, now);
        if (!expirationTime.isAfter(now)) {
            Parcel existing = parcels.get(key);
            if (existing != null) {
                existing.register(null);
            }
            return;
        }
        Parcel parcel = findOrCreate(key, now);
        boolean renewal = parcel.hasActiveRegistration(now);
        parcel.register(new WebhookRegistration(callbackUrl, expirationTime));
        if (!renewal) {
            // 처음 등록하면 알림이 한 번 간다(Delivery Tracker와 같은 규칙). 등록 전에 일어난 사건은 이 한 건으로 갈음한다
            parcel.materialize(playback, now);
            parcel.takeUnnotified();
            enqueue(parcel.key(), callbackUrl, 1, now);
        }
        schedule(parcel);
    }

    /**
     * 지금 보낼 알림을 꺼낸다. 먼저 등록된 송장 중 상태가 바뀐 것의 알림을 대기 줄에 넣고,
     * 꺼내는 간격이 지났으면 한 번에 정한 개수까지 돌려준다. 꺼낸 알림은 결과를 {@link #completeCallback}으로 알려야 한다.
     */
    public synchronized List<CallbackAttempt> pollDueCallbacks(Instant now) {
        advance(now);
        if (dispatch.paused()) {
            return List.of();
        }
        if (lastDispatchAt != null && now.isBefore(lastDispatchAt.plus(dispatch.interval()))) {
            return List.of();
        }
        List<CallbackAttempt> due = new ArrayList<>();
        while (due.size() < dispatch.batchSize()) {
            CallbackAttempt attempt = callbackQueue.pollDue(now);
            if (attempt == null) {
                break;
            }
            Parcel parcel = parcels.get(attempt.key());
            // 등록이 만료됐거나 송장을 지웠으면 보내지 않는다
            if (parcel != null && parcel.hasActiveRegistration(now)) {
                due.add(attempt);
            }
        }
        if (due.isEmpty()) {
            return due;
        }
        // 실제로 꺼냈을 때만 간격을 센다. 비어 있다가 막 들어온 알림이 한 간격을 기다리지 않게 한다
        lastDispatchAt = now;
        if (faults.shuffleCallbacks()) {
            Collections.shuffle(due, shuffleRandom);
        }
        return due;
    }

    /**
     * 알림을 보낸 결과를 기록한다. 실패했으면 간격을 늘려(1초, 2초, 4초… 최대 5분) 만료 시각 전까지 다시 보낸다.
     */
    public synchronized void completeCallback(CallbackAttempt attempt, CallbackOutcome outcome, Instant now) {
        Parcel parcel = parcels.get(attempt.key());
        callbackRecords.addFirst(new CallbackRecord(now.minusMillis(outcome.elapsedMillis()), attempt.key(),
                parcel == null ? null : parcel.lastStatus(), outcome.responseCode(), outcome.failure(),
                outcome.elapsedMillis(), attempt.attemptNo()));
        if (callbackRecords.size() > MAX_CALLBACK_RECORDS) {
            callbackRecords.removeLast();
        }
        if (outcome.success()) {
            stats.record(Metric.CALLBACK_OK, now);
            return;
        }
        stats.record(Metric.CALLBACK_FAIL, now);
        if (parcel == null || parcel.registration() == null) {
            return;
        }
        Instant retryAt = now.plus(retryDelay(attempt.attemptNo()));
        if (retryAt.isBefore(parcel.registration().expirationTime())) {
            callbackQueue.add(new CallbackAttempt(nextCallbackId++, attempt.key(), attempt.callbackUrl(),
                    attempt.attemptNo() + 1), retryAt);
        }
    }

    public synchronized DispatchSettings dispatch() {
        return dispatch;
    }

    public synchronized void updateDispatch(DispatchSettings dispatch) {
        if (dispatch == null) {
            throw new IllegalArgumentException("전송 설정은 필수입니다.");
        }
        this.dispatch = dispatch;
    }

    /**
     * 보낸 알림의 기록을 최근 순으로 돌려준다.
     *
     * @param numberPrefix 송장 번호 앞자리. 비어 있으면 거르지 않는다
     * @param limit        돌려줄 개수. 1 이상 1,000 이하
     */
    public synchronized List<CallbackRecord> callbackRecords(String numberPrefix, int limit) {
        if (limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException("기록 개수는 1 이상 1,000 이하여야 합니다.");
        }
        String prefix = numberPrefix == null ? "" : numberPrefix.strip();
        return callbackRecords.stream()
                .filter(record -> record.key().trackingNumber().startsWith(prefix))
                .limit(limit)
                .toList();
    }

    public synchronized String issueToken(Instant now) {
        return tokens.issue(now);
    }

    public synchronized long tokenTtlSeconds() {
        return tokens.ttl().toSeconds();
    }

    /**
     * 발급한 토큰을 모두 무효로 만든다. 다음 요청은 UNAUTHENTICATED를 받는다.
     */
    public synchronized void expireTokens() {
        tokens.expireAll();
    }

    /**
     * GraphQL 요청을 받아들일지 정한다. 토큰 확인과 장애 주입(지연, 오류 비율)을 여기서 한다.
     *
     * @param token Authorization 헤더에서 Bearer를 뗀 값. 없으면 null
     */
    public synchronized Admission admit(String token, Instant now) {
        long delay = faults.responseDelayMillis();
        if (!tokens.isValid(token, now)) {
            stats.record(Metric.UNAUTHENTICATED, now);
            return new Admission(delay, Admission.Rejection.UNAUTHENTICATED);
        }
        // 비율이 0이면 난수를 쓰지 않는다. 장애를 끈 동안의 요청 수가 뒤의 장애 순서를 바꾸지 않게 한다
        if (faults.errorPercent() > 0 && faultRandom.nextInt(100) < faults.errorPercent()) {
            stats.record(Metric.TRACK_ERROR, now);
            return new Admission(delay, Admission.Rejection.INTERNAL);
        }
        return new Admission(delay, Admission.Rejection.NONE);
    }

    public synchronized void changeSpeed(double secondsPerDay, Instant now) {
        playback.changeSpeed(secondsPerDay, now);
    }

    public synchronized void pause(Instant now) {
        playback.pause(now);
    }

    public synchronized void resume(Instant now) {
        playback.resume(now);
    }

    public synchronized void skipDay(Instant now) {
        playback.skip(Duration.ofDays(1), now);
    }

    public synchronized double secondsPerDay() {
        return playback.secondsPerDay();
    }

    public synchronized boolean paused() {
        return playback.paused();
    }

    public synchronized FaultSettings faults() {
        return faults;
    }

    public synchronized void updateFaults(FaultSettings faults) {
        if (faults == null) {
            throw new IllegalArgumentException("장애 설정은 필수입니다.");
        }
        this.faults = faults;
    }

    public synchronized StatsSnapshot stats(Instant now) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (StatusCode status : StatusCode.values()) {
            statusCounts.put(status.displayName(), 0L);
        }
        statusCounts.put(NOT_FOUND_LABEL, 0L);
        for (Parcel parcel : parcelOrder) {
            parcel.materialize(playback, now);
            StatusCode status = parcel.lastStatus();
            statusCounts.merge(status == null ? NOT_FOUND_LABEL : status.displayName(), 1L, Long::sum);
        }
        return new StatsSnapshot(stats.totals(), stats.buckets(now), statusCounts, parcelOrder.size(),
                pendingCallbacks(), percentile(0.50), percentile(0.95));
    }

    /**
     * 송장 목록을 만든 순서로 쪽 나눠 돌려준다.
     *
     * @param page 0부터 시작하는 쪽 번호
     * @param size 쪽 크기. 1 이상 200 이하
     */
    public synchronized ParcelPage parcels(ParcelFilter filter, int page, int size, Instant now) {
        if (page < 0) {
            throw new IllegalArgumentException("쪽 번호는 0 이상이어야 합니다.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("쪽 크기는 1 이상 200 이하여야 합니다.");
        }
        ParcelFilter condition = filter == null ? ParcelFilter.all() : filter;
        long offset = (long) page * size;
        List<ParcelView> items = new ArrayList<>();
        if (condition.isEmpty()) {
            for (long i = offset; i < Math.min(offset + size, parcelOrder.size()); i++) {
                Parcel parcel = parcelOrder.get((int) i);
                parcel.materialize(playback, now);
                items.add(parcel.view());
            }
            return new ParcelPage(items, parcelOrder.size());
        }
        long matched = 0;
        for (Parcel parcel : parcelOrder) {
            if (!matchesKey(parcel, condition)) {
                continue;
            }
            parcel.materialize(playback, now);
            if (condition.status() != null && parcel.lastStatus() != condition.status()) {
                continue;
            }
            if (matched >= offset && items.size() < size) {
                items.add(parcel.view());
            }
            matched++;
        }
        return new ParcelPage(items, matched);
    }

    public synchronized Optional<ParcelDetail> parcel(ParcelKey key, Instant now) {
        Parcel parcel = parcels.get(key);
        if (parcel == null) {
            return Optional.empty();
        }
        parcel.materialize(playback, now);
        List<ParcelDetail.EventTrace> events = parcel.entries().stream()
                .map(entry -> new ParcelDetail.EventTrace(entry.event, entry.firstFetchedAt))
                .toList();
        return Optional.of(new ParcelDetail(parcel.view(), parcel.registration(), events));
    }

    /**
     * 숫자와 그래프만 지운다. 송장은 남는다.
     */
    public synchronized void clearStats() {
        stats.clear();
        detectionNanos.clear();
        callbackRecords.clear();
    }

    /**
     * 송장까지 모두 지운다. 재생 속도, 전송 속도, 장애 설정, 발급한 토큰은 남는다.
     */
    public synchronized void clearAll() {
        clearStats();
        parcels.clear();
        parcelOrder.clear();
        callbackQueue.clear();
        schedule.clear();
        lastDispatchAt = null;
    }

    // 등록된 송장 중 새 사건이 보일 때가 된 것을 찾아 알림을 넣는다
    private void advance(Instant now) {
        Instant virtualNow = playback.virtualNow(now);
        while (!schedule.isEmpty() && !schedule.peek().virtualTime().isAfter(virtualNow)) {
            Parcel parcel = schedule.poll().parcel();
            parcel.scheduled(false);
            // 모두 지운 뒤 남아 있던 항목은 건너뛴다
            if (parcels.get(parcel.key()) != parcel || !parcel.hasActiveRegistration(now)) {
                continue;
            }
            parcel.materialize(playback, now);
            int changes = parcel.takeUnnotified();
            for (int i = 0; i < changes; i++) {
                enqueue(parcel.key(), parcel.registration().callbackUrl(), faults.callbackCopies(), now);
            }
            schedule(parcel);
        }
    }

    private void schedule(Parcel parcel) {
        Instant next = parcel.nextVisibleVirtualTime();
        if (next == null || parcel.scheduled()) {
            return;
        }
        parcel.scheduled(true);
        schedule.add(new Scheduled(next, nextScheduleSequence++, parcel));
    }

    private void enqueue(ParcelKey key, String callbackUrl, int copies, Instant now) {
        for (int i = 0; i < copies; i++) {
            callbackQueue.add(new CallbackAttempt(nextCallbackId++, key, callbackUrl, 1), now);
        }
    }

    private static Duration retryDelay(int attemptNo) {
        // 지수가 커지면 넘치므로 상한(5분)에 닿는 지점에서 끊는다
        int exponent = Math.min(attemptNo - 1, 20);
        Duration delay = Duration.ofSeconds(1L << exponent);
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private Parcel findOrCreate(ParcelKey key, Instant now) {
        Parcel existing = parcels.get(key);
        if (existing != null) {
            return existing;
        }
        Parcel created = new Parcel(key, Scenario.of(key.trackingNumber()), now, playback.virtualNow(now), factorOf(key));
        parcels.put(key, created);
        parcelOrder.add(created);
        return created;
    }

    // 시드와 송장 키로만 정해지므로 같은 송장은 실행할 때마다 같은 배율을 받는다
    private double factorOf(ParcelKey key) {
        double jitter = settings.jitter();
        if (jitter == 0) {
            return 1;
        }
        double random = new Random(settings.seed() * 31 + key.hashCode()).nextDouble();
        return 1 - jitter + 2 * jitter * random;
    }

    private static boolean matchesKey(Parcel parcel, ParcelFilter filter) {
        if (filter.scenario() != null && parcel.scenario() != filter.scenario()) {
            return false;
        }
        String prefix = filter.numberPrefix();
        return prefix == null || prefix.isBlank() || parcel.key().trackingNumber().startsWith(prefix.strip());
    }

    private void addDetection(Duration latency) {
        detectionNanos.addLast(latency.toNanos());
        if (detectionNanos.size() > MAX_LATENCY_SAMPLES) {
            detectionNanos.removeFirst();
        }
    }

    private Duration percentile(double ratio) {
        if (detectionNanos.isEmpty()) {
            return null;
        }
        long[] sorted = detectionNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(ratio * sorted.length) - 1;
        return Duration.ofNanos(sorted[Math.max(0, index)]);
    }

    private long pendingCallbacks() {
        return callbackQueue.size();
    }

    private record Scheduled(Instant virtualTime, long sequence, Parcel parcel) {
    }
}
