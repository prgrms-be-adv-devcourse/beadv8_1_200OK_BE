package com.ok.member.member.adapter.out.event;

import com.ok.member.member.application.port.out.MemberMessageSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 통합 테스트에서 실제 Kafka 발송기({@link KafkaMemberMessageSender})를 감싸, 다음 한 번만 실패시킬 수 있게 하는 발송기다 (#560).
 * <p>테스트가 함께 쓰는 Kafka 컨테이너는 멈출 수 없어서 브로커 장애를 이렇게 흉내 낸다. {@code @MockitoSpyBean}을 쓰면 스프링 컨텍스트가
 * 따로 떠서 느려지므로(testing-strategy.md), 모듈 전용 테스트 지원 클래스로 한 번만 등록해 모든 통합 테스트가 같은 컨텍스트를 쓰게 한다.
 * 실패를 걸지 않으면 실제 발송기에 그대로 넘겨 다른 테스트에는 영향이 없다.
 */
@Primary
@Component
public class FailableMemberMessageSender implements MemberMessageSender {

    private final KafkaMemberMessageSender delegate;
    private final AtomicReference<RuntimeException> nextFailure = new AtomicReference<>();

    FailableMemberMessageSender(KafkaMemberMessageSender delegate) {
        this.delegate = delegate;
    }

    /**
     * 다음 발송 한 번만 주어진 예외로 실패시킨다. 그다음부터는 실제로 보낸다.
     */
    public void failNextSend(RuntimeException failure) {
        nextFailure.set(failure);
    }

    /**
     * 걸어 둔 실패를 지운다. 테스트가 실패를 쓰지 않고 끝나도 다음 테스트로 넘어가지 않게 정리할 때 쓴다.
     */
    public void reset() {
        nextFailure.set(null);
    }

    @Override
    public void sendMemberRegistered(Long memberId) {
        RuntimeException failure = nextFailure.getAndSet(null);
        if (failure != null) {
            throw failure;
        }
        delegate.sendMemberRegistered(memberId);
    }
}
