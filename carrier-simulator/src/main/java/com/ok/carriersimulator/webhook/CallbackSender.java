package com.ok.carriersimulator.webhook;

import com.ok.carriersimulator.core.CallbackAttempt;
import com.ok.carriersimulator.core.CallbackOutcome;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 알림 한 건을 받는 쪽에 보낸다. Delivery Tracker처럼 본문에는 택배사와 송장 번호만 담고, 받는 쪽이 1초 안에 답하기를 기다린다.
 * 어떤 실패도 예외로 던지지 않고 결과에 담는다. 한 건의 실패가 다른 알림을 막지 않게 하기 위해서다.
 */
@Component
public class CallbackSender {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    public CallbackOutcome send(CallbackAttempt attempt) {
        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(attempt.callbackUrl()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(attempt), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return CallbackOutcome.responded(response.statusCode(), elapsedMillis(startedAt));
        } catch (HttpTimeoutException e) {
            return CallbackOutcome.failed("시간 초과", elapsedMillis(startedAt));
        } catch (IOException e) {
            return CallbackOutcome.failed("연결 실패", elapsedMillis(startedAt));
        } catch (IllegalArgumentException e) {
            return CallbackOutcome.failed("잘못된 주소", elapsedMillis(startedAt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CallbackOutcome.failed("중단됨", elapsedMillis(startedAt));
        }
    }

    static String body(CallbackAttempt attempt) {
        return "{\"carrierId\":\"" + escape(attempt.key().carrierId())
                + "\",\"trackingNumber\":\"" + escape(attempt.key().trackingNumber()) + "\"}";
    }

    // 값은 화면에서 사람이 넣을 수도 있어 따옴표나 줄바꿈이 섞여도 JSON이 깨지지 않게 한다
    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
