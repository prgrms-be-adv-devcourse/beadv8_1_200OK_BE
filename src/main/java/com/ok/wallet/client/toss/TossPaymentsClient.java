package com.ok.wallet.client.toss;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Set;

/**
 * 토스페이먼츠 결제 승인 API 클라이언트. 트랜잭션 밖에서 호출해야 한다.
 * 응답을 승인·명확한 실패·불명확 세 가지로 나누며, 확실하지 않으면 불명확으로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentsClient {
    private static final String CONFIRM_PATH = "/v1/payments/confirm";
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
            "NOT_AVAILABLE_PAYMENT"           // 결제 불가 시간대
    );

    private final RestClient tossRestClient;

    /**
     * 결제를 승인한다. 같은 충전 건으로 다시 호출해도 토스가 한 번만 승인하도록 충전 번호를 멱등 키로 쓴다.
     *
     * @param paymentKey 토스가 결제창에서 발급한 결제 키
     * @param chargeNo   충전 번호 (토스의 orderId)
     * @param amount     서버에 저장된 충전 금액
     */
    public TossConfirmResult confirm(String paymentKey, String chargeNo, long amount) {
        try {
            TossConfirmResponse response = tossRestClient.post()
                    .uri(CONFIRM_PATH)
                    .header(IDEMPOTENCY_KEY_HEADER, chargeNo)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TossConfirmRequest(paymentKey, chargeNo, amount))
                    .retrieve()
                    .body(TossConfirmResponse.class);
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

    // 응답은 시스템이 넘긴 값이라 이 경계에서 확인한다. 요청과 다르면 승인으로 믿지 않는다.
    // 결제 키는 잔액 반영 근거 키(CHARGE:{paymentKey})가 되므로 주문 번호·금액과 같은 수준으로 대조한다.
    private TossConfirmResult toApprovedOrUnknown(TossConfirmResponse response, String paymentKey,
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