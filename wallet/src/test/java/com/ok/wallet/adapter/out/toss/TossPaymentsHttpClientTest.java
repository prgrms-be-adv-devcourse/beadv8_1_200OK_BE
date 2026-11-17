package com.ok.wallet.adapter.out.toss;

import com.ok.wallet.application.dto.TossConfirmResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.stream.Stream;

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
class TossPaymentsHttpClientTest {
    private static final String BASE_URL = "https://api.tosspayments.test";
    private static final String CONFIRM_URL = BASE_URL + "/v1/payments/confirm";
    private static final String PAYMENT_KEY = "payment-key-1";
    private static final String CHARGE_NO = "CHG_test-charge-no";
    private static final String LOOKUP_URL = BASE_URL + "/v1/payments/orders/" + CHARGE_NO;
    private static final long AMOUNT = 10_000;

    private MockRestServiceServer server;
    private TossPaymentsHttpClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TossPaymentsHttpClient(builder.build());
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

    @Test
    void 존재하지_않는_결제라는_응답은_명확한_실패로_분류한다() {
        // 잘못된 결제 키로 처리 중에 갇혀 한도를 차지하지 않도록 실패로 정리한다
        server.expect(requestTo(CONFIRM_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")));

        assertThat(client.confirm(PAYMENT_KEY, CHARGE_NO, AMOUNT))
                .isInstanceOf(TossConfirmResult.Rejected.class);
    }

    // 결제 조회

    @Test
    void 결제가_있으면_결제_키와_상태와_금액을_돌려주고_충전_번호로_조회한다() {
        // given
        server.expect(requestTo(LOOKUP_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, "DONE", AMOUNT), MediaType.APPLICATION_JSON));

        // when
        TossPaymentLookupResult sut = client.findPayment(CHARGE_NO);

        // then
        assertThat(sut).isEqualTo(new TossPaymentLookupResult.Found(PAYMENT_KEY, TossPaymentStatus.DONE, AMOUNT));
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("토스_결제_상태")
    void 토스_문서의_결제_상태는_모두_그대로_돌려준다(TossPaymentStatus status) {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, status.name(), AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.findPayment(CHARGE_NO))
                .isEqualTo(new TossPaymentLookupResult.Found(PAYMENT_KEY, status, AMOUNT));
    }

    static Stream<TossPaymentStatus> 토스_결제_상태() {
        return Stream.of(TossPaymentStatus.values());
    }

    @Test
    void 결제가_없다는_코드면_결제_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("NOT_FOUND_PAYMENT", "존재하지 않는 결제 정보 입니다.")));

        assertThat(client.findPayment(CHARGE_NO)).isEqualTo(new TossPaymentLookupResult.NotFound());
    }

    @Test
    void 같은_404여도_뜻이_넓은_코드는_알_수_없음으로_분류한다() {
        // 주소 설정 오류에서도 나올 수 있어, 결제 없음으로 보면 승인된 충전을 실패로 바꿀 수 있다
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse("NOT_FOUND", "존재하지 않는 정보 입니다.")));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    @ParameterizedTest
    @MethodSource("결제_없음이_아닌_조회_오류")
    void 결제_없음이_아닌_조회_오류는_알_수_없음으로_분류한다(HttpStatus status, String code) {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse(code, "오류")));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    static Stream<Arguments> 결제_없음이_아닌_조회_오류() {
        return Stream.of(
                Arguments.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED_KEY"),
                Arguments.of(HttpStatus.FORBIDDEN, "FORBIDDEN_CONSECUTIVE_REQUEST"),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING"));
    }

    @Test
    void 조회_오류_본문을_읽을_수_없으면_알_수_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    @Test
    void 조회_응답_타임아웃은_알_수_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    @Test
    void 조회_응답의_주문_번호가_충전_번호와_다르면_알_수_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withSuccess(approvedResponse("CHG_other", "DONE", AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    @Test
    void 조회_응답의_상태가_토스_문서에_없는_값이면_알_수_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withSuccess(approvedResponse(CHARGE_NO, "SOMETHING_NEW", AMOUNT), MediaType.APPLICATION_JSON));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }

    @Test
    void 조회_응답에_금액이_없으면_알_수_없음으로_분류한다() {
        server.expect(requestTo(LOOKUP_URL))
                .andRespond(withSuccess("""
                    {"paymentKey": "%s", "orderId": "%s", "status": "DONE"}
                    """.formatted(PAYMENT_KEY, CHARGE_NO), MediaType.APPLICATION_JSON));

        assertThat(client.findPayment(CHARGE_NO)).isInstanceOf(TossPaymentLookupResult.Unknown.class);
    }
}
