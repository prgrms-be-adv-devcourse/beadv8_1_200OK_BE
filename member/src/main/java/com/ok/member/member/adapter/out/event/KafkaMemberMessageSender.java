package com.ok.member.member.adapter.out.event;

import com.ok.member.member.application.port.out.MemberMessageSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link MemberMessageSender}의 Kafka 구현체. 회원가입을 {@link MemberTopics#MEMBER_REGISTERED}로 보낸다.
 * 한 건마다 브로커의 저장 확인(acks=all)을 최대 {@link #SEND_TIMEOUT}까지 기다리고, 실패하면 회원 ID를 담은 예외를 던진다.
 * KafkaTemplate은 스프링 부트가 yaml(spring.kafka)로 만든 것을 그대로 쓴다. 모듈 전용으로 새로 만들면 한 앱(monolith)에
 * KafkaTemplate이 둘이 되어 다른 모듈의 주입이 모호해질 수 있다.
 */
@Component
class KafkaMemberMessageSender implements MemberMessageSender {

    // 저장 확인을 기다리는 최대 시간. outbox 발송 작업이 스케줄러 스레드에서 기다리므로, Kafka 기본값(delivery.timeout.ms 120초)까지
    // 기다리면 브로커 장애 때 같은 스레드를 쓰는 다른 스케줄 작업(Refresh Token 정리 등)이 오래 밀린다. 정상이면 1초 안에 끝난다.
    // 실패해도 outbox 줄이 PENDING으로 남아 다음 실행(30초 뒤)에서 다시 보내므로 오래 기다릴 이유가 없다
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Duration sendTimeout;

    @Autowired
    KafkaMemberMessageSender(KafkaTemplate<String, Object> kafkaTemplate) {
        this(kafkaTemplate, SEND_TIMEOUT);
    }

    // 테스트가 시간 초과 경로를 10초씩 기다리지 않고 확인할 수 있게 대기 시간을 바꿔 넣는다
    KafkaMemberMessageSender(KafkaTemplate<String, Object> kafkaTemplate, Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void sendMemberRegistered(Long memberId) {
        // 키를 memberId로 두면 같은 회원의 메시지는 같은 파티션으로 가서 순서가 지켜진다
        String key = String.valueOf(memberId);
        try {
            // 저장 확인을 기다린다. 기다리지 않으면 브로커가 거절해도 outbox 줄이 "보냄"으로 바뀌어 다시 보내지 않는다.
            // 이 시간 제한은 send() 뒤의 기다림만 줄인다. send() 안에서 Kafka 주소를 찾느라 기다리는 시간(max.block.ms 기본 60초)은
            // producer 설정이라 그대로다(refund와 같은 한계, #516)
            kafkaTemplate.send(MemberTopics.MEMBER_REGISTERED, key, new MemberRegisteredMessage(memberId))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 기다리는 중 스레드가 중단되면 중단 표시를 되살린 뒤 실패로 올린다
            Thread.currentThread().interrupt();
            throw sendFailed(memberId, e);
        } catch (ExecutionException e) {
            throw sendFailed(memberId, e.getCause());
        } catch (TimeoutException e) {
            // 기다리기만 그만둔 것이라 메시지는 뒤에서 계속 보내질 수 있다. 그러면 다시 보낼 때 같은 메시지가 두 번 가지만,
            // 받는 쪽(장바구니)은 회원마다 한 번만 만들어 중복에 안전하다(#560 메시지 약속)
            throw sendFailed(memberId, e);
        } catch (RuntimeException e) {
            // send()를 부르는 순간 바로 나는 오류(직렬화 실패, 보낼 자리를 기다리다 시간 초과 등)도 어느 회원인지 남긴다
            throw sendFailed(memberId, e);
        }
    }

    // 사용자 입력이 아니라 브로커 쪽 실패라 표준 예외를 쓴다 (exception.md: 값의 출처 기준)
    private static IllegalStateException sendFailed(Long memberId, Throwable cause) {
        return new IllegalStateException("회원가입 Kafka 발행에 실패했습니다. memberId=" + memberId, cause);
    }
}
