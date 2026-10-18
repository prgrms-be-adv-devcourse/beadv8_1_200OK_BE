package com.ok.wallet.client.toss;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

// 실제 토스를 호출하지 않고 응답을 흉내 내, 승인·명확한 실패·불명확 분류를 확인한다.
// 분류가 틀리면 결제된 돈이 잔액에 반영되지 않거나, 결제 안 된 돈이 반영되므로 경계 사례를 모두 확인한다.
class TossPaymentsClientTest {
    private static final String BASE_URL = "https://api.tosspayments.test";
    private static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final String CHARGE_NO = "CHG_test-charge-no";
    private static final long AMOUNT = 10_000;

    private MockRestServiceServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsClient(builder.build());
    }

    // 승인

    @Test
    void 승인_응답이면_승인으로_분류하고_충전_번호를_멱등_키와_주문_번호로_보낸다() {
        // Given
        server.expect(requestTo(CONFIRM_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", CHARGE_NO))
                .andExpect(content().json("""
                        {"paymentKey": "%s", "orderId": "%s", "amount": %d}
                        """.formatted(PAYMENT_KEY, CHARGE_NO, AMOUNT)))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, "DONE", AMOUNT), MediaType.APPLICATION_JSON));

        // When
        TossConfirmResult result = client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT);

        // Then
        assertThat(result).isEqualTo(new TossConfirmResult.Approved(PAYMENT_KEY));
        server.verify();
    }

    @Test
    void 승인_응답의_금액이_요청과_다르면_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, "DONE", 20_000), MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 승인_응답의_주문_번호가_요청과_다르면_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withSuccess(approvedResponse("CHG_other", "DONE", AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 승인_응답의_상태가_완료가_아니면_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, "IN_PROGRESS", AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    // 명확한 실패

    @Test
    void 토스가_오류_코드로_거절하면_명확한_실패로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isEqualTo(new TossConfirmResult.Rejected(
                        "REJECT_CARD_PAYMENT", "한도초과 혹은 잔액부족으로 결제에 실패했습니다."));
    }

    // 불명확

    @Test
    void 같은_멱등_요청이_처리_중이라는_응답은_불명확으로_분류한다() {
        // 4xx지만 이전 요청이 승인됐을 수 있으므로 실패로 처리하지 않는다
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("IDEMPOTENT_REQUEST_PROCESSING", "이전 멱등 요청이 처리 중입니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 이미_처리된_결제라는_응답은_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제입니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 오류_응답_본문을_읽을_수_없으면_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 토스_서버_오류는_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withServerError());

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 응답_타임아웃은_불명확으로_분류한다() {
        // 토스가 요청을 받아 승인했을 수도 있으므로 실패로 처리하지 않는다
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    private static String approvedResponse(String orderId, String status, long totalAmount) {
        return """
                {"paymentKey": "%s", "orderId": "%s", "status": "%s", "totalAmount": %d}
                """.formatted(PAYMENT_KEY, orderId, status, totalAmount);
    }

    private static String errorResponse(String code, String message) {
        return """
                {"code": "%s", "message": "%s"}
                """.formatted(code, message);
    }

    @Test
    void 우리_쪽_키_설정_오류는_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("UNAUTHORIZED_KEY", "인증되지 않은 시크릿 키입니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 요청_제한은_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("TOO_MANY_REQUESTS", "요청이 많습니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 거절_목록에_없는_오류_코드는_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("FAILED_INTERNAL_SYSTEM_PROCESSING", "내부 시스템 처리 작업이 실패했습니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 승인_응답의_결제_키가_요청과_다르면_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withSuccess("""
                    {"paymentKey": "other-payment-key", "orderId": "%s", "status": "DONE", "totalAmount": %d}
                    """.formatted(CHARGE_NO, AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }

    @Test
    void 결제_세션이_없다는_응답은_불명확으로_분류한다() {
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("NOT_FOUND_PAYMENT_SESSION", "결제 시간이 만료되어 결제 진행 데이터가 존재하지 않습니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Unknown.class);
    }
}
