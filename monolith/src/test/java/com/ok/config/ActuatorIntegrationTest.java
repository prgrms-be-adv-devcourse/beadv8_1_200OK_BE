package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;

import com.ok.PaldoGotganApplication;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

/**
 * 관리 포트로 분리한 actuator가 health와 prometheus만, 상세 없이 노출하는지 검증한다.
 * 관리 포트는 별도 서버라 MockMvc로는 볼 수 없어서 실제 서버를 띄워 두 포트에 HTTP로 요청한다.
 */
@SpringBootTest(
        classes = PaldoGotganApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ActuatorIntegrationTest extends AbstractIntegrationTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    TestAccessTokens tokens;

    @Value("${local.server.port}")
    int serverPort;

    @Value("${local.management.port}")
    int managementPort;

    // 컨테이너 헬스체크가 토큰 없이 호출하므로, 공개 경로(SecurityConfig.PUBLIC_PATHS)로 열려 있어야 한다
    @Test
    void health는_상세_없이_상태만_응답한다() throws Exception {
        // when
        HttpResponse<String> sut = get(managementPort, "/actuator/health");

        // then
        assertThat(sut.statusCode()).isEqualTo(200);
        assertThat(sut.body()).contains("\"status\":\"UP\"").doesNotContain("components");
    }

    // Prometheus가 토큰 없이 수집하므로, 공개 경로(SecurityConfig.PUBLIC_PATHS)로 열려 있어야 한다
    @Test
    void prometheus는_토큰_없이_application_태그가_붙은_지표를_응답한다() throws Exception {
        // when
        HttpResponse<String> sut = get(managementPort, "/actuator/prometheus");

        // then
        assertThat(sut.statusCode()).isEqualTo(200);
        assertThat(sut.body())
                .contains("# HELP jvm_memory_used_bytes")
                .contains("application=\"paldo-gotgan\"");
    }

    // 기본 거부라 토큰이 없으면 노출 여부를 보기 전에 401이 나므로, 노출 여부는 토큰을 붙여 본다
    @Test
    void health와_prometheus_외의_actuator_엔드포인트는_노출하지_않는다() throws Exception {
        // when
        HttpResponse<String> sut = get(managementPort, "/actuator/env", tokens.bearer(1L, "ADMIN"));

        // then
        assertThat(sut.statusCode()).isEqualTo(404);
    }

    // 관리 포트에도 Security가 적용되는지 확인한다. 공개 경로는 health와 prometheus뿐이다
    @Test
    void health와_prometheus_외의_actuator_경로는_토큰_없이_호출하면_401을_반환한다() throws Exception {
        // when
        HttpResponse<String> sut = get(managementPort, "/actuator/env");

        // then
        assertThat(sut.statusCode()).isEqualTo(401);
    }

    // 관리 엔드포인트가 서비스 포트(nginx가 프록시하는 8080)로 새면 외부에 노출된다
    @Test
    void 서비스_포트에서는_actuator를_응답하지_않는다() throws Exception {
        // when
        HttpResponse<String> health = get(serverPort, "/actuator/health");
        HttpResponse<String> prometheus = get(serverPort, "/actuator/prometheus");

        // then
        assertThat(health.statusCode()).isEqualTo(404);
        assertThat(prometheus.statusCode()).isEqualTo(404);
    }

    // uri 라벨에 실제 ID가 들어가면 요청마다 시계열이 늘어 Prometheus가 감당하지 못한다
    @Test
    void 경로_변수가_있는_API의_uri_태그는_템플릿으로_남는다() throws Exception {
        // given
        get(serverPort, "/api/v1/products/987654321");
        get(serverPort, "/api/v1/products/123456789");

        // when: 지표는 응답을 보낸 뒤 기록되므로 라벨이 나타날 때까지 기다린다
        String sut = awaitPrometheusContaining("uri=\"/api/v1/products/{productId}\"");

        // then
        assertThat(sut)
                .doesNotContain("987654321")
                .doesNotContain("123456789");
    }

    // 로그인한 요청이 없는 경로를 부르면 정적 리소스 핸들러가 받아 404를 내므로, NOT_FOUND가 아니라 핸들러 패턴(/**)으로 묶인다
    // 토큰이 없으면 401(uri=UNKNOWN)로 끝나 이 경우를 볼 수 없다
    @Test
    void 없는_경로는_uri_태그가_하나로_묶인다() throws Exception {
        // given
        String token = tokens.bearer(1L, "ADMIN");
        get(serverPort, "/no-such-path/abc-1", token);
        get(serverPort, "/no-such-path/abc-2", token);

        // when: 지표는 응답을 보낸 뒤 기록되므로 라벨이 나타날 때까지 기다린다
        String sut = awaitPrometheusContaining("uri=\"/**\"");

        // then
        assertThat(sut).doesNotContain("no-such-path");
    }

    private String awaitPrometheusContaining(String label) {
        AtomicReference<String> body = new AtomicReference<>("");
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            body.set(get(managementPort, "/actuator/prometheus").body());
            assertThat(body.get()).contains(label);
        });
        return body.get();
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return get(port, path, null);
    }

    private HttpResponse<String> get(int port, String path, String authorization)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
