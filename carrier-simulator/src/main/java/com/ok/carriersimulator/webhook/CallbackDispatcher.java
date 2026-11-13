package com.ok.carriersimulator.webhook;

import com.ok.carriersimulator.config.SimulatorProperties;
import com.ok.carriersimulator.core.CallbackAttempt;
import com.ok.carriersimulator.core.CallbackOutcome;
import com.ok.carriersimulator.core.CarrierSimulator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기 줄에서 꺼낸 알림을 보내고 결과를 core에 돌려준다.
 * 무엇을 언제 몇 번 보낼지는 core가 정하고, 여기는 타이머와 동시 전송 수 제한만 맡는다.
 */
@Slf4j
@Component
public class CallbackDispatcher implements DisposableBean {

    private final CarrierSimulator simulator;
    private final CallbackSender sender;
    private final Clock clock;
    private final boolean tickerEnabled;
    // 전송은 대부분 응답을 기다리는 시간이라 가상 스레드로 보낸다
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    // 꺼냈지만 아직 결과가 나오지 않은 알림 수
    private int inFlight;
    // 지금 보내고 있는 알림 수
    private int sending;

    CallbackDispatcher(CarrierSimulator simulator, CallbackSender sender, Clock clock, SimulatorProperties properties) {
        this.simulator = simulator;
        this.sender = sender;
        this.clock = clock;
        this.tickerEnabled = properties.tickerEnabled();
    }

    @Scheduled(fixedDelay = 100)
    void scheduledTick() {
        if (tickerEnabled) {
            tick();
        }
    }

    /**
     * 지금 보낼 알림을 꺼내 보내기 시작한다. 전송이 끝나기를 기다리지 않는다.
     * 앞서 꺼낸 묶음이 아직 끝나지 않았으면 새로 꺼내지 않는다. 받는 쪽이 느릴 때 꺼낸 알림이 메모리에 쌓이지 않고
     * 대기 줄에 남아 화면의 대기 건수로 보이게 하기 위해서다.
     */
    public void tick() {
        lock.lock();
        try {
            if (inFlight > 0) {
                return;
            }
            List<CallbackAttempt> attempts = simulator.pollDueCallbacks(clock.instant());
            inFlight = attempts.size();
            for (CallbackAttempt attempt : attempts) {
                executor.submit(() -> deliver(attempt));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 꺼낸 알림이 모두 끝날 때까지 기다린다.
     *
     * @return 시간 안에 모두 끝났으면 true
     */
    public boolean awaitIdle(Duration timeout) {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (inFlight > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = changed.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void deliver(CallbackAttempt attempt) {
        try {
            acquire();
            try {
                CallbackOutcome outcome = sender.send(attempt);
                simulator.completeCallback(attempt, outcome, clock.instant());
            } finally {
                release();
            }
        } catch (RuntimeException e) {
            // 여기서 예외가 새면 이 알림의 결과가 기록되지 않는다. 다른 알림은 계속 보내야 하므로 남기고 넘어간다
            log.warn("알림 전송 처리 실패. key={}", attempt.key(), e);
        } finally {
            finished();
        }
    }

    // 동시 전송 수는 실행 중에 화면에서 바뀔 수 있어 매번 지금 설정을 읽는다
    private void acquire() {
        lock.lock();
        try {
            while (sending >= simulator.dispatch().concurrency()) {
                changed.awaitUninterruptibly();
            }
            sending++;
        } finally {
            lock.unlock();
        }
    }

    private void release() {
        lock.lock();
        try {
            sending--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void finished() {
        lock.lock();
        try {
            inFlight--;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }
}
