package com.ok.payout.adapter.in.batch;

import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ok.payout.application.port.in.PayoutPublishResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * outbox에 남은(PENDING) 정산 메시지를 짧은 간격으로 다시 보낸다 (#620, 상위 #308).
 * 지갑은 Kafka 메시지가 와야 입금하므로(#584), 정산 Job 발행 단계에서 못 보낸 줄을 다음 날 정산 Job까지 기다리지 않게 한다.
 * 대사에서 "입금 없음"으로 되돌린 줄도 여기서 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutOutboxResendScheduler {

    private final PayoutOutboxSendLock payoutOutboxSendLock;

    // 간격은 설정 값, 기본 5분. Kafka가 살아난 뒤 최대 5분 안에 보내지고, 보낼 줄이 없으면 PENDING 조회 1번(인덱스)으로 끝남
    // fixedDelay: 앞 실행이 끝난 뒤부터 셈. 브로커가 느려 한 번이 길어져도 실행이 겹쳐 쌓이지 않음
    // 첫 실행도 앱 시작 5분 뒤: 앱이 뜨는 중에 Kafka 연결을 서두르지 않고, 통합 테스트가 컨텍스트를 띄우자마자 끼어들지 않게
    @Scheduled(fixedDelayString = "${payout.outbox-resend.fixed-delay:PT5M}",
            initialDelayString = "${payout.outbox-resend.fixed-delay:PT5M}")
    public void resendPending() {
        Optional<PayoutPublishResult> result = payoutOutboxSendLock.publishPending();
        if (result.isEmpty()) {
            // 정산 Job 발행 단계나 다른 서버의 재발송이 지금 보내는 중이라 정상. 남은 줄은 그쪽이 보내거나 다음 실행이 보냄
            log.info("다른 곳이 정산 outbox를 보내는 중이라 이번 재발송을 건너뜀");
        }
        // 보낸 결과 로그는 publishPending이 남김. 0건이어도 남겨 재발송이 5분마다 돌고 있다는 흔적을 둠 (하루 최대 288줄)
    }
}
