package com.ok.carriersimulator.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 알림을 받는 쪽(delivery)을 대신하는 작은 HTTP 서버. 받은 본문을 기억하고, 응답 코드와 지연을 테스트가 정한다.
 */
public class FakeReceiver implements AutoCloseable {

    private final HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final AtomicInteger concurrent = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private volatile int status = 202;
    private volatile Duration delay = Duration.ZERO;
    private volatile Duration stall = Duration.ZERO;

    public FakeReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/callback", exchange -> {
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Thread.sleep(delay.toMillis());
                if (stall.isZero()) {
                    exchange.sendResponseHeaders(status, -1);
                } else {
                    // 헤더는 바로 보내고, 약속한 본문은 보내지 않은 채 연결을 잡아 둔다
                    exchange.sendResponseHeaders(status, 10);
                    exchange.getResponseBody().flush();
                    Thread.sleep(stall.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
    }

    public void respondWith(int status) {
        this.status = status;
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    /**
     * 응답 헤더만 보내고 본문을 끝내지 않은 채 그 시간 동안 연결을 잡아 둔다.
     */
    public void stallBody(Duration stall) {
        this.stall = stall;
    }

    public List<String> bodies() {
        return List.copyOf(bodies);
    }

    public int maxConcurrent() {
        return maxConcurrent.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
