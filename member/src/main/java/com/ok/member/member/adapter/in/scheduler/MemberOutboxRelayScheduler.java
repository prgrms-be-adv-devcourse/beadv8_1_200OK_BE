package com.ok.member.member.adapter.in.scheduler;

import com.ok.member.member.application.port.in.MemberOutboxRelayUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 회원 outbox에 쌓인 메시지를 정해진 주기(기본 30초)로 보낸다 (#560).
 *
 * <p>주기와 한 번에 보낼 줄 수는 {@code member.outbox.relay.cron}, {@code member.outbox.relay.batch-size}로 바꾼다.
 * cron을 {@code -}로 두면 돌지 않는다(테스트는 끄고 직접 부른다).
 *
 * <p>서버가 여러 대면 같은 줄을 함께 보낼 수 있다. 받는 쪽이 중복에 안전하고(#560 메시지 약속) member는 아직 한 대라(#442) 잠금을 두지 않는다.
 */
@Component
public class MemberOutboxRelayScheduler {

    private final MemberOutboxRelayUseCase memberOutboxRelay;
    private final int batchSize;

    // 설정이 없으면 0이 아니라 기본값(100)이 들어가도록 기본값을 @Value에 함께 둔다
    public MemberOutboxRelayScheduler(MemberOutboxRelayUseCase memberOutboxRelay,
                                      @Value("${member.outbox.relay.batch-size:100}") int batchSize) {
        this.memberOutboxRelay = memberOutboxRelay;
        this.batchSize = batchSize;
    }

    // 30초마다. 장바구니는 메시지가 늦어도 처음 쓸 때 만들어 버티므로 짧게 잡을 이유가 없고, 보낼 줄이 없으면 인덱스 조회 한 번으로 끝난다.
    // 특정 시각이 아니라 짧은 주기라 운영 서버가 떠 있는 동안(09~18시) 계속 돈다.
    // cron 작업은 앞 실행이 끝난 뒤에 다음 실행 시각을 잡으므로, 브로커 장애로 오래 걸려도 자기 실행끼리 겹치지 않는다
    @Scheduled(cron = "${member.outbox.relay.cron:*/30 * * * * *}", zone = "Asia/Seoul")
    public void run() {
        memberOutboxRelay.relayPending(batchSize);
    }

}
