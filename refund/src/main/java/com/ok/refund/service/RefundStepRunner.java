package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.RefundFailedStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * 승인 뒤 다른 모듈 호출(지갑·주문·재고) 한 단계를 실행하고, 실패하면 멈춘 단계와 이유를 환불에 남긴다.
 * 1단계에는 자동으로 다시 처리하는 방법이 없어, 운영자가 이 기록을 보고 어디부터 다시 할지 판단한다.
 * #413·#414의 Facade가 트랜잭션 밖에서 부른다. 기록은 {@link RefundService#recordFailure}의 트랜잭션으로 따로 저장된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundStepRunner {

    private final RefundService refundService;

    /**
     * 한 단계를 실행해 결과를 돌려준다. 실패하면 단계와 이유를 기록한 뒤 원래 예외를 그대로 던진다.
     * 기록마저 실패하면 기록 실패를 원래 예외에 붙여(suppressed) 원래 예외를 던진다.
     *
     * @param call 다른 모듈 호출. 돌려줄 값이 없으면 {@code null}을 돌려준다
     * @throws IllegalStateException 이미 열린 트랜잭션 안에서 부르면 (부르는 쪽 버그). 호출 전에 막는다
     */
    public <T> T run(Long refundId, RefundFailedStep step, Supplier<T> call) {
        requireNoTransaction();
        try {
            return call.get();
        } catch (RuntimeException e) {
            recordFailure(refundId, step, e);
            throw e;
        }
    }

    // 기록은 바깥 트랜잭션 없이 따로 커밋돼야 한다. 바깥 트랜잭션 안에서 부르면 기록이 거기에 합류해,
    // 이어서 던지는 원래 예외로 바깥이 롤백될 때 기록도 함께 사라진다. 기록 자체는 성공했으니 ERROR 로그도 남지 않아 아무도 모른다.
    // 그래서 다른 모듈을 부르기 전에 막는다. Facade는 트랜잭션을 두지 않으므로(service.md) 정상 경로에서는 걸리지 않는다
    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("환불 단계 실행은 트랜잭션 밖에서 불러야 합니다. 실패 기록이 바깥 트랜잭션과 함께 롤백됩니다.");
        }
    }

    // 응답은 원래 예외로 한다. 사용자에게 필요한 건 "지갑이 응답하지 않았다" 같은 진짜 원인이고,
    // 기록 실패(DB 순간 장애 등)로 응답을 바꾸면 그 원인이 가려진다.
    // 대신 기록이 없으면 처리 중인 건과 멈춘 건이 구분되지 않아 운영자가 놓치므로, 기록 실패는 원래 예외에 붙이고 ERROR로 남긴다.
    // 원래 예외가 RestApiException(*_API_FAILED 등)이면 ApiControllerAdvice가 응답만 만들고 로그를 남기지 않아, 이 로그가 기록 실패의 유일한 흔적이다.
    // 원래 예외가 그 밖의 예외면 advice가 suppressed까지 함께 남겨 기록 실패가 두 번 찍히지만, 드문 경우라 놓치지 않는 쪽을 택한다
    private void recordFailure(Long refundId, RefundFailedStep step, RuntimeException cause) {
        String failureCode = failureCodeOf(cause);
        try {
            refundService.recordFailure(refundId, step, failureCode);
        } catch (RuntimeException recordError) {
            cause.addSuppressed(recordError);
            log.error("환불 실패 단계 기록 실패. 처리 중에 기록 없이 남음. refundId={}, step={}, failureCode={}",
                    refundId, step, failureCode, recordError);
        }
    }

    // 예외 메시지에는 다른 모듈의 응답 내용이 들어갈 수 있어 DB에 남기지 않고, 에러 코드 이름이나 예외 클래스 이름만 쓴다
    private static String failureCodeOf(RuntimeException e) {
        if (e instanceof RestApiException restApiException) {
            return restApiException.getErrorCode().name();
        }
        return e.getClass().getSimpleName();
    }
}
