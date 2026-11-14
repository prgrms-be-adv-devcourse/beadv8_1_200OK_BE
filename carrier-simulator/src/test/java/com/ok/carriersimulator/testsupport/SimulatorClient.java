package com.ok.carriersimulator.testsupport;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 띄운 서버에 실제 HTTP로 요청한다. delivery의 TrackerCarrierClient가 보내는 것과 같은 모양으로 보낸다.
 */
public class SimulatorClient {

    private final Environment environment;
    private final RestClient restClient = RestClient.create();

    public SimulatorClient(Environment environment) {
        this.environment = environment;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port");
    }

    public HttpResult requestToken(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(baseUrl() + "/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> new HttpResult(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    public String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", "test-client");
        form.add("client_secret", "test-secret");
        return JsonPath.read(requestToken(form).body(), "$.access_token");
    }

    /**
     * @param token null이면 Authorization 헤더를 보내지 않는다
     */
    public HttpResult graphql(String query, Map<String, Object> variables, String token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("variables", variables);
        RestClient.RequestBodySpec spec = restClient.post()
                .uri(baseUrl() + "/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
        if (token != null) {
            spec = spec.header("Authorization", "Bearer " + token);
        }
        return spec.body(body)
                .exchange((request, response) -> new HttpResult(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    public record HttpResult(int status, String body) {

        public <T> T read(String jsonPath) {
            return JsonPath.read(body, jsonPath);
        }
    }
}
