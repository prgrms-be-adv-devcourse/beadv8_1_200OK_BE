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

    // 정산 전체 수동 실행 (개발 중 수동 실행용. 권한·실패 응답은 이번 범위 밖, #52)
    @PostMapping("/run")
    public ResponseEntity<PayoutRunResponse> run() throws Exception {
        JobExecution execution = payoutJobRunner.run();
        return ResponseEntity.ok(new PayoutRunResponse(execution.getId(), execution.getStatus().name()));
    }
    // TODO: API 작업 시 응답 DTO 위치·형식 정리
    public record PayoutRunResponse(Long executionId, String status) {
    }
}
