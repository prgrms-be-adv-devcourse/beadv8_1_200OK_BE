package com.ok.payout.controller;

import com.ok.payout.service.PayoutJobRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payout")
@RequiredArgsConstructor
public class PayoutController {

    private final PayoutJobRunner payoutJobRunner;

    // 정산 전체 수동 실행
    //현재 테스트에서 제외 일단 범위에서 진행 예정.
    @PostMapping("/run")
    public ResponseEntity<PayoutRunResponse> run() throws Exception {
        JobExecution execution = payoutJobRunner.run();
        return ResponseEntity.ok(new PayoutRunResponse(execution.getId(), execution.getStatus().name()));
    }
    //todo 응답룰이 없음 임시로 사용
    public record PayoutRunResponse(Long executionId, String status) {
    }
}
