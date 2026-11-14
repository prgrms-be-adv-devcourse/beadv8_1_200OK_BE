package com.ok.member.member.application.service;

import com.ok.member.member.application.dto.MemberOutboxMessage;
import com.ok.member.member.application.port.in.MemberOutboxRelayUseCase;
import com.ok.member.member.application.port.out.MemberMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 회원 outbox에 쌓인 메시지를 쌓인 순서대로 보낸다 (#560).
 * 브로커 확인을 기다리는 동안(최대 10초) DB 커넥션을 잡지 않도록 트랜잭션 없이 실행하고,
 * 줄 읽기와 결과 저장은 {@link MemberOutboxService}의 짧은 트랜잭션에 맡긴다.
 *
 * <p>한 줄이라도 실패하면 그 실행은 거기서 멈춘다. 이유는 둘이다.
 * 실패는 거의 브로커 장애라 뒤 줄도 줄마다 10초씩 기다리다 실패할 뿐이고,
 * 멈추면 앞 줄이 나가기 전에 같은 회원의 뒤 줄이 먼저 나가는 일이 없다(같은 회원의 메시지 순서를 지킴).
 * 대신 같은 줄이 계속 실패하면 뒤 줄이 모두 기다린다. 그래서 {@link #ESCALATE_ATTEMPT_COUNT}번을 넘게 실패하면 ERROR로 남겨 눈에 띄게 한다.
 * 지금 메시지는 회원 ID만 담아 브로커가 복구되면 나가므로, 본문이 있는 메시지를 더할 때 이 정책을 다시 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberOutboxRelayFacade implements MemberOutboxRelayUseCase {

    // 이만큼 실패하면(30초 주기로 약 5분) 잠깐의 장애가 아니라 사람이 봐야 하는 문제로 본다
    static final int ESCALATE_ATTEMPT_COUNT = 10;

    // 실패 원인을 따라가는 최대 단계. 실제 사슬은 3~4단계라 넉넉히 둔다
    private static final int MAX_CAUSE_DEPTH = 20;

    private final MemberOutboxService memberOutboxService;
    private final MemberMessageSender memberMessageSender;

    @Override
    public int relayPending(int batchSize) {
        List<MemberOutboxMessage> pending = memberOutboxService.findPending(batchSize);
        int sent = 0;
        for (MemberOutboxMessage message : pending) {
            try {
                send(message);
            } catch (RuntimeException e) {
                handleFailure(message, e, pending.size() - sent - 1);
                break;
            }
            // 보낸 뒤 "보냄" 기록이 실패하면 예외가 올라가 실행이 멈추고, 그 줄은 PENDING으로 남아 다음 실행에서 한 번 더 나간다.
            // 받는 쪽(장바구니)은 같은 회원 메시지를 두 번 받아도 한 번만 만든다(#560 메시지 약속)
            memberOutboxService.markSent(message.outboxId());
            sent++;
        }
        return sent;
    }

    private void send(MemberOutboxMessage message) {
        switch (message.eventType()) {
            case MEMBER_REGISTERED -> memberMessageSender.sendMemberRegistered(message.memberId());
            case SELLER_ORIGIN_POSTAL_CODE_CHANGED ->
                    memberMessageSender.sendSellerOriginPostalCodeChanged(message.memberId(), message.payload());
        }
    }

    // 예외를 다시 던지지 않고 줄에 남긴다. 던지면 스케줄러가 30초마다 같은 오류를 찍고, 실패 횟수도 남지 않는다.
    // 줄이 PENDING으로 남아 다음 실행에서 다시 나가므로 평소에는 WARN이고, 오래 계속되면 ERROR다(service.md 로그 표)
    private void handleFailure(MemberOutboxMessage message, RuntimeException sendFailure, int skipped) {
        int attemptCount = message.attemptCount() + 1;
        // 기록보다 로그를 먼저 남긴다. 기록이 DB 오류로 실패해도 원래 발송 실패 원인이 로그에 남게 하려는 것이다
        if (attemptCount > ESCALATE_ATTEMPT_COUNT) {
            log.error("회원 outbox 발송이 계속 실패해 뒤 줄이 막혀 있음, 확인 필요. outboxId={}, memberId={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.memberId(), attemptCount, skipped, sendFailure);
        } else {
            log.warn("회원 outbox 발송 실패, 이번 실행을 멈춤. outboxId={}, memberId={}, 시도={}, 남은 줄={}",
                    message.outboxId(), message.memberId(), attemptCount, skipped, sendFailure);
        }
        try {
            memberOutboxService.recordFailure(message.outboxId(), failureReason(sendFailure));
        } catch (RuntimeException recordFailure) {
            // 기록이 실패해도 줄은 PENDING이라 다음 실행에서 다시 나간다. 위로 올리는 예외에 발송 실패를 붙여 원인을 함께 남긴다
            recordFailure.addSuppressed(sendFailure);
            throw recordFailure;
        }
    }

    // 발송기 예외는 회원 ID를 담고, 실제 이유(브로커 거절, 시간 초과)는 원인 사슬 맨 안쪽에 있다. 둘을 함께 남긴다.
    // 원인이 서로를 가리키는 순환이 있으면 끝없이 돌아 발송 스케줄러가 멈추므로, 따라가는 단계 수에 상한을 둔다
    static String failureReason(Throwable e) {
        Throwable root = e;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        return root == e ? describe(e) : describe(e) + " / " + describe(root);
    }

    // 메시지가 없는 예외는 "null"이 아니라 예외 이름만 남긴다. null이라 실패한 것처럼 읽히지 않게 하려는 것이다
    private static String describe(Throwable e) {
        String name = e.getClass().getSimpleName();
        return e.getMessage() == null ? name : name + ": " + e.getMessage();
    }
}
