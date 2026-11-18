package com.ok.wallet.adapter.out.toss;

import com.ok.wallet.application.dto.TossCancelResult;
import com.ok.wallet.application.dto.TossConfirmResult;
import com.ok.wallet.application.dto.TossPaymentLookupResult;
import com.ok.wallet.application.dto.TossPaymentStatus;
import com.ok.wallet.application.port.out.TossPaymentsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.Set;

/**
 * {@link TossPaymentsClient}의 HTTP 구현. 토스페이먼츠 결제 승인·조회·취소 API를 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentsHttpClient implements TossPaymentsClient {
    private static final String CONFIRM_PATH = "/v1/payments/confirm";
    private static final String LOOKUP_BY_ORDER_ID_PATH = "/v1/payments/orders/{orderId}";
    private static final String CANCEL_PATH = "/v1/payments/{paymentKey}/cancel";
    private static final String CANCELED_STATUS = "CANCELED";
    private static final String ALREADY_CANCELED_CODE = "ALREADY_CANCELED_PAYMENT";
    // 조회에서 결제가 없다는 뜻이 분명한 코드는 이것 하나다. 같은 404인 NOT_FOUND는 뜻이 넓어(주소 설정 오류 등) 알 수 없음으로 둔다
    private static final String PAYMENT_NOT_FOUND_CODE = "NOT_FOUND_PAYMENT";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String APPROVED_STATUS = "DONE";

    // 결제가 승인되지 않았음이 확실한 거절 코드만 실패로 본다. 목록에 없는 코드는 불명확으로 두어
    // 복구 스케줄러가 토스에 다시 확인하게 한다 (승인된 결제를 실패로 처리하지 않기 위해).
    // 코드 이름은 토스페이먼츠 결제 승인 API 오류 코드 문서 기준
    // NOT_FOUND_PAYMENT_SESSION은 넣지 않는다: 세션 만료인지 이미 처리돼 세션이 정리된 것인지 응답만으로 구분할 수 없어
    // 불명확으로 두고 복구 스케줄러가 토스 결제 조회로 확인한다
    private static final Set<String> REJECTED_ERROR_CODES = Set.of(
            "REJECT_CARD_PAYMENT",            // 한도 초과 또는 잔액 부족
            "REJECT_CARD_COMPANY",            // 카드사 거절
            "REJECT_ACCOUNT_PAYMENT",         // 계좌 잔액 부족
            "INVALID_CARD_EXPIRATION",        // 유효기간 오류
            "INVALID_STOPPED_CARD",           // 정지된 카드
            "INVALID_CARD_LOST_OR_STOLEN",    // 분실·도난 카드
            "INVALID_CARD_NUMBER",            // 카드 번호 오류
            "INVALID_PASSWORD",               // 비밀번호 오류
            "EXCEED_MAX_DAILY_PAYMENT_COUNT", // 하루 결제 횟수 초과
            "EXCEED_MAX_PAYMENT_AMOUNT",      // 결제 한도 초과
            "NOT_AVAILABLE_PAYMENT",          // 결제 불가 시간대
            "NOT_FOUND_PAYMENT"               // 존재하지 않는 결제 (잘못된 결제 키): 승인될 결제가 없음
            // INVALID_REQUEST는 넣지 않는다: 범위가 넓어 승인 불가가 보장되지 않으므로 불명확으로 두고 복구 스케줄러가 확인한다
    );

    // 취소되지 않았음이 확실한 거절 코드만 거절로 본다. 목록에 없는 코드(일시 오류, 처리 중, 요청 제한, 키 설정 오류 등)는
    // 취소됐는지 알 수 없음으로 둔다. 코드 이름은 토스페이먼츠 결제 취소 API 오류 코드 문서 기준
    private static final Set<String> CANCEL_REJECTED_ERROR_CODES = Set.of(
            "NOT_CANCELABLE_PAYMENT",                 // 취소할 수 없는 결제 (승인되지 않은 결제 등)
            "NOT_CANCELABLE_AMOUNT",                  // 취소할 수 없는 금액
            "NOT_FOUND_PAYMENT",                      // 존재하지 않는 결제
            "EXCEED_MAX_REFUND_DUE",                  // 환불 가능 기간이 지남
            "EXCEED_CANCEL_LIMIT",                    // 취소 한도 초과
            "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER", // 휴면 회원의 결제
            "REFUND_REJECTED"                         // 결제사가 환불을 거절
    );

    private final RestClient tossRestClient;

    @Override
    public TossConfirmResult confirm(String paymentKey, String chargeNo, long amount) {
        try {
            TossPaymentResponse response = tossRestClient.post()
                    .uri(CONFIRM_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, chargeNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossConfirmRequest(paymentKey, chargeNo, amount))
                    .retrieve()
                    .body(TossPaymentResponse.class);
            return toApprovedOrUnknown(response, paymentKey, chargeNo, amount);
        } catch (HttpClientErrorException e) {
            return fromClientError(e, chargeNo);
        } catch (HttpServerErrorException e) {
            return unknown(chargeNo, "토스 서버 오류 " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // 연결 실패, 응답 타임아웃: 토스가 요청을 받아 승인했을 수도 있다
            return unknown(chargeNo, "토스 응답 없음: " + e.getMessage());
        } catch (RestClientException e) {
            return unknown(chargeNo, "토스 응답 처리 실패: " + e.getMessage());
        }
    }

    @Override
    public TossPaymentLookupResult findPayment(String chargeNo) {
        try {
            TossPaymentResponse response = tossRestClient.get()
                    .uri(LOOKUP_BY_ORDER_ID_PATH, chargeNo)
                    .retrieve()
                    .body(TossPaymentResponse.class);
            return toFoundOrUnknown(response, chargeNo);
        } catch (HttpClientErrorException e) {
            TossErrorResponse error = e.getResponseBodyAs(TossErrorResponse.class);
            if (error != null && PAYMENT_NOT_FOUND_CODE.equals(error.code())) {
                return new TossPaymentLookupResult.NotFound();
            }
            return lookupUnknown(chargeNo, "토스 조회 오류 " + e.getStatusCode().value()
                    + " " + (error == null ? null : error.code()));
        } catch (HttpServerErrorException e) {
            return lookupUnknown(chargeNo, "토스 서버 오류 " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return lookupUnknown(chargeNo, "토스 응답 없음: " + e.getMessage());
        } catch (RestClientException e) {
            return lookupUnknown(chargeNo, "토스 응답 처리 실패: " + e.getMessage());
        }
    }

    @Override
    public TossCancelResult cancel(String paymentKey, String chargeNo, String cancelReason, String idempotencyKey) {
        try {
            TossPaymentResponse response = tossRestClient.post()
                    .uri(CANCEL_PATH, paymentKey)
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossCancelRequest(cancelReason))
                    .retrieve()
                    .body(TossPaymentResponse.class);
            return toCanceledOrUnknown(response, paymentKey, chargeNo);
        } catch (HttpClientErrorException e) {
            return fromCancelClientError(e, chargeNo);
        } catch (HttpServerErrorException e) {
            return cancelUnknown(chargeNo, "토스 서버 오류 " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            // 연결 실패, 응답 타임아웃: 토스가 요청을 받아 취소했을 수도 있다
            return cancelUnknown(chargeNo, "토스 응답 없음: " + e.getMessage());
        } catch (RestClientException e) {
            return cancelUnknown(chargeNo, "토스 응답 처리 실패: " + e.getMessage());
        }
    }

    // 다른 결제의 응답이거나 전액 취소 상태가 아니면 취소됐다고 믿지 않는다
    private TossCancelResult toCanceledOrUnknown(TossPaymentResponse response, String paymentKey, String chargeNo) {
        if (response == null
                || !paymentKey.equals(response.paymentKey())
                || !chargeNo.equals(response.orderId())
                || !CANCELED_STATUS.equals(response.status())) {
            return cancelUnknown(chargeNo, "취소 응답이 요청과 맞지 않음. orderId="
                    + (response == null ? null : response.orderId())
                    + ", status=" + (response == null ? null : response.status()));
        }
        return new TossCancelResult.Canceled();
    }

    private TossCancelResult fromCancelClientError(HttpClientErrorException e, String chargeNo) {
        TossErrorResponse error = e.getResponseBodyAs(TossErrorResponse.class);
        if (error == null || error.code() == null) {
            return cancelUnknown(chargeNo, "토스 오류 응답을 읽을 수 없음 " + e.getStatusCode().value());
        }
        if (ALREADY_CANCELED_CODE.equals(error.code())) {
            return new TossCancelResult.AlreadyCanceled();
        }
        if (!CANCEL_REJECTED_ERROR_CODES.contains(error.code())) {
            return cancelUnknown(chargeNo, "취소 여부 불명확: " + e.getStatusCode().value() + " " + error.code());
        }
        log.info("토스 결제 취소 거절. chargeNo={}, code={}, message={}", chargeNo, error.code(), error.message());
        return new TossCancelResult.Rejected(error.code(), error.message());
    }

    private TossCancelResult cancelUnknown(String chargeNo, String reason) {
        log.warn("토스 결제 취소 결과를 알 수 없습니다. chargeNo={}, reason={}", chargeNo, reason);
        return new TossCancelResult.Unknown(reason);
    }

    // 다른 충전 건의 결제이거나 모르는 상태면 그 답으로 충전 건을 바꾸지 않는다
    private TossPaymentLookupResult toFoundOrUnknown(TossPaymentResponse response, String chargeNo) {
        if (response == null || !chargeNo.equals(response.orderId()) || response.totalAmount() == null) {
            return lookupUnknown(chargeNo, "조회 응답이 충전 건과 맞지 않음. orderId="
                    + (response == null ? null : response.orderId()));
        }
        TossPaymentStatus status = toStatus(response.status());
        if (status == null) {
            return lookupUnknown(chargeNo, "모르는 결제 상태 " + response.status());
        }
        return new TossPaymentLookupResult.Found(response.paymentKey(), status, response.totalAmount());
    }

    private static TossPaymentStatus toStatus(String status) {
        return Arrays.stream(TossPaymentStatus.values())
                .filter(value -> value.name().equals(status))
                .findFirst()
                .orElse(null);
    }

    private TossPaymentLookupResult lookupUnknown(String chargeNo, String reason) {
        log.warn("토스 결제 조회 결과를 알 수 없어 다음에 다시 확인합니다. chargeNo={}, reason={}", chargeNo, reason);
        return new TossPaymentLookupResult.Unknown(reason);
    }

    // 응답은 시스템이 넘긴 값이라 이 경계에서 확인한다. 요청과 다르면 승인으로 믿지 않는다.
    // 결제 키는 잔액 반영 근거 키(CHARGE:{paymentKey})가 되므로 주문 번호·금액과 같은 수준으로 대조한다.
    private TossConfirmResult toApprovedOrUnknown(TossPaymentResponse response, String paymentKey,
                                                  String chargeNo, long amount) {
        if (response == null
                || !paymentKey.equals(response.paymentKey())
                || !chargeNo.equals(response.orderId())
                || !APPROVED_STATUS.equals(response.status())
                || response.totalAmount() == null
                || response.totalAmount() != amount) {
            // 결제 키는 결제 식별자라 로그에 남기지 않고, 판단에 필요한 값만 남긴다
            log.error("토스 승인 응답이 요청과 맞지 않습니다. chargeNo={}, orderId={}, status={}, totalAmount={}",
                    chargeNo,
                    response == null ? null : response.orderId(),
                    response == null ? null : response.status(),
                    response == null ? null : response.totalAmount());
            return new TossConfirmResult.Unknown("승인 응답이 요청과 맞지 않음");
        }
        return new TossConfirmResult.Approved(response.paymentKey());
    }

    private TossConfirmResult fromClientError(HttpClientErrorException e, String chargeNo) {
        TossErrorResponse error = e.getResponseBodyAs(TossErrorResponse.class);
        if (error == null || error.code() == null) {
            return unknown(chargeNo, "토스 오류 응답을 읽을 수 없음 " + e.getStatusCode().value());
        }
        if (!REJECTED_ERROR_CODES.contains(error.code())) {
            // 키 설정 오류(401), 요청 제한(429), 처리 중·이미 처리됨, 토스 내부 처리 실패 등은 승인 여부를 알 수 없다
            return unknown(chargeNo, "승인 여부 불명확: " + e.getStatusCode().value() + " " + error.code());
        }
        log.info("토스 승인 거절. chargeNo={}, code={}, message={}", chargeNo, error.code(), error.message());
        return new TossConfirmResult.Rejected(error.code(), error.message());
    }

    private TossConfirmResult unknown(String chargeNo, String reason) {
        log.warn("토스 승인 결과를 알 수 없어 처리 중으로 둡니다. chargeNo={}, reason={}", chargeNo, reason);
        return new TossConfirmResult.Unknown(reason);
    }
}
