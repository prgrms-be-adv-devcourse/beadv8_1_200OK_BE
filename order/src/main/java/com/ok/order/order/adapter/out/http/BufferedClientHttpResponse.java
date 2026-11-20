package com.ok.order.order.adapter.out.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 상태 코드·헤더·본문을 미리 다 읽어 메모리에 담아 둔 응답이다 (#739).
 * {@link InternalRestClient}가 요청을 감싸는 구간 안에서 응답을 끝까지 읽어, 읽는 도중의 시간 초과도 그 구간에서 잡게 하려는 것이다.
 * 그 뒤 클라이언트가 본문을 변환할 때는 네트워크를 기다리지 않는다. 내부 API 응답은 작은 JSON이라 메모리에 담아도 된다.
 */
final class BufferedClientHttpResponse implements ClientHttpResponse {

    private final HttpStatusCode statusCode;
    private final String statusText;
    private final HttpHeaders headers;
    private final byte[] body;

    private BufferedClientHttpResponse(HttpStatusCode statusCode, String statusText, HttpHeaders headers, byte[] body) {
        this.statusCode = statusCode;
        this.statusText = statusText;
        this.headers = headers;
        this.body = body;
    }

    /**
     * 응답을 끝까지 읽어 담고 원래 응답은 닫는다.
     *
     * @throws IOException 상태 코드나 본문을 읽다가 끊기거나 시간이 초과되면
     */
    static ClientHttpResponse readFully(ClientHttpResponse response) throws IOException {
        try (response) {
            HttpStatusCode statusCode = response.getStatusCode();
            String statusText = response.getStatusText();
            HttpHeaders headers = HttpHeaders.copyOf(response.getHeaders());
            byte[] body = response.getBody().readAllBytes();
            return new BufferedClientHttpResponse(statusCode, statusText, headers, body);
        }
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    @Override
    public String getStatusText() {
        return statusText;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    // 상태 처리기(onStatus)와 본문 변환이 각각 읽어도 되도록 부를 때마다 처음부터 읽는 스트림을 준다
    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body);
    }

    // 원래 응답은 readFully에서 이미 닫았다
    @Override
    public void close() {
    }
}
