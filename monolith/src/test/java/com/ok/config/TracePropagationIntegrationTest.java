package com.ok.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.ok.PaldoGotganApplication;
import com.ok.common.trace.TraceHeaderInterceptor;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * 진입 요청의 traceId·orderId가 실제 필터와 실행기를 거쳐 두 경계(비동기 리스너, 내부 HTTP)를 넘어 이어지는지 확인한다.
 * 진입 요청 → 같은 요청 안에서 (A) 이벤트 발행 → 비동기 리스너, (B) 내부 HTTP 호출 → 받는 쪽 컨트롤러.
 * 실제 주문·상품·지갑 모듈 코드는 쓰지 않고, 경계에서 MDC를 기록하는 시험용 빈으로 확인한다.
 */
@SpringBootTest(classes = PaldoGotganApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TracePropagationIntegrationTest.ProbeConfig.class)
class TracePropagationIntegrationTest extends AbstractIntegrationTest {

    static final AtomicReference<Map<String, String>> LISTENER_MDC = new AtomicReference<>();
    static final AtomicReference<Map<String, String>> INTERNAL_MDC = new AtomicReference<>();

    record ProbeEvent(String name) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfig {

        // 컨트롤러 클래스는 컴포넌트 스캔에 잡혀 다른 통합 테스트에도 노출되므로 함수형 라우트로 등록한다
        @Bean
        RouterFunction<ServerResponse> probeRoutes(ApplicationEventPublisher publisher, TransactionTemplate tx,
                                                   Environment environment) {
            return RouterFunctions.route()
                    .GET("/internal/v1/trace-probe/start", request -> {
                        // 비동기 리스너는 커밋 뒤 실행되므로 트랜잭션 안에서 발행한다
                        tx.executeWithoutResult(status -> publisher.publishEvent(new ProbeEvent("probe")));
                        // 서버 포트는 기동 뒤에 정해지므로 호출할 때 읽는다
                        RestClient internalClient = RestClient.builder()
                                .baseUrl("http://127.0.0.1:" + environment.getProperty("local.server.port"))
                                .requestInterceptor(new TraceHeaderInterceptor())
                                .build();
                        internalClient.get().uri("/internal/v1/trace-probe/echo").retrieve().toBodilessEntity();
                        return ServerResponse.ok().build();
                    })
                    .GET("/internal/v1/trace-probe/echo", request -> {
                        INTERNAL_MDC.set(MDC.getCopyOfContextMap());
                        return ServerResponse.ok().build();
                    })
                    .build();
        }

        @Bean
        ProbeListener probeListener() {
            return new ProbeListener();
        }
    }

    static class ProbeListener {

        @ApplicationModuleListener
        void on(ProbeEvent event) {
            LISTENER_MDC.set(MDC.getCopyOfContextMap());
        }
    }

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void reset() {
        LISTENER_MDC.set(null);
        INTERNAL_MDC.set(null);
    }

    @Test
    void 진입_요청의_traceId와_orderId가_비동기_리스너와_내부_HTTP_호출까지_이어진다() {
        // given
        RestClient client = RestClient.create("http://127.0.0.1:" + port);

        // when
        client.get().uri("/internal/v1/trace-probe/start")
                .header("X-Trace-Id", "123456789012345")
                .header("X-Order-Id", "987654321")
                .retrieve().toBodilessEntity();

        // then
        await().atMost(Duration.ofSeconds(10)).until(() -> LISTENER_MDC.get() != null);
        assertThat(LISTENER_MDC.get()).containsEntry("traceId", "123456789012345").containsEntry("orderId", "987654321");
        assertThat(INTERNAL_MDC.get()).containsEntry("traceId", "123456789012345").containsEntry("orderId", "987654321");
    }
}
