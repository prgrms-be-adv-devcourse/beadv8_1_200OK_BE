package com.ok.payout.adapter.in.batch;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.type.PayoutRunType;
import com.ok.payout.exception.PayoutErrorCode;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

import java.time.LocalDateTime;

// 정산 Job을 시작하기 전에 필수 파라미터(requestedAt, runType)를 확인함 (#485)
// Job에 붙인 검사기는 JobExecution을 만들기 전에 돌아서, 잘못된 실행이 FAILED 정산 기록(스냅샷, 실행 기록)으로 남지 않음
// 예) 앱 시작 시 자동 실행처럼 파라미터 없이 시작된 경우 → 실행 기록 없이 바로 예외
// 던지는 예외는 표준 예외가 아닌 RestApiException(PayoutErrorCode): 정산 배치 경로의 예외는 정산 에러 코드를 씀 (exception.md)
// 검사기가 던지는 예외는 Spring Batch가 감싸지 않고 그대로 호출자(JobOperator.start)에게 전달됨 (통합 테스트로 확인)
public class PayoutJobParametersValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) {
        // 없음만 정산 에러 코드로 막음. 타입이 다른 값(예: requestedAt을 문자열로 넣음)은 Spring Batch가 꺼낼 때
        // IllegalArgumentException을 던지는데, 이 경우도 JobExecution을 만들기 전이라 기록이 남지 않음.
        // 파라미터는 PayoutJobRunner만 만들고 타입을 고정해 넣으므로 그대로 둠
        LocalDateTime requestedAt = parameters.getLocalDateTime("requestedAt");
        if (requestedAt == null) {
            throw new RestApiException(PayoutErrorCode.MISSING_PAYOUT_REQUESTED_AT);
        }

        // runType은 기록용이지만, 비어 있거나 모르는 값이면 어느 경로로 시작됐는지 알 수 없는 스냅샷이 남으므로 막음
        String runType = parameters.getString("runType");
        if (runType == null || !isRunTypeName(runType)) {
            throw new RestApiException(PayoutErrorCode.INVALID_PAYOUT_RUN_TYPE);
        }
    }

    // valueOf 예외로 분기하지 않고 이름을 직접 비교함 (공백·소문자 등 이름과 다른 값은 모르는 값으로 취급)
    private boolean isRunTypeName(String value) {
        for (PayoutRunType type : PayoutRunType.values()) {
            if (type.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
