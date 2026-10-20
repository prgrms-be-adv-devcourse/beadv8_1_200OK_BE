package com.ok.payout.controller;

import com.ok.payout.controller.dto.PayoutRunResponse;
import com.ok.payout.service.PayoutJobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 정산 대기(15일) 건너뛰기 실행 API (#224, 발표·연동 테스트용 임시 기능)
// 관리자 정산 API(PayoutAdminController)와 같은 경로(/admin/payouts) 아래에 두고, prod에는 등록하지 않음 (SecurityConfig 전환 기간, #108)
// 임시 기능이라 지울 때 이 파일과 Spec만 지우면 되도록 PayoutAdminController와 분리함
@Profile("!prod")
@RestController
@RequestMapping("/api/v1/admin/payouts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class PayoutSkipWaitRunController implements PayoutSkipWaitRunControllerSpec {

    private final PayoutJobRunner payoutJobRunner;

    @Override
    @PostMapping("/run/skip-wait")
    public ResponseEntity<PayoutRunResponse> runSkippingWait() throws Exception {
        JobExecution execution = payoutJobRunner.runSkippingWait();
        // 실패 처리는 /run과 같음 (PayoutController.run 주석 참고)
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            throw new IllegalStateException(
                    "정산 대기 건너뛰기 실행에 실패했습니다. executionId=" + execution.getId() + ", status=" + execution.getStatus());
        }
        return ResponseEntity.ok(new PayoutRunResponse(execution.getId(), execution.getStatus().name()));
    }
}
