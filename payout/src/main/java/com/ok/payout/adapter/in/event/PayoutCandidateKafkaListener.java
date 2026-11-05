package com.ok.payout.adapter.in.event;

import com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.application.port.in.PayoutCandidateUseCase;
import com.ok.payout.application.port.in.PurchaseConfirmedCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 주문 구매확정을 Kafka로 받는 입구다.
 * Spring 이벤트 입구({@link PayoutCandidateEventListener})는 #343에서 지워질 예정이라 따로 분리했고, 두 입구는 같은 입력 포트를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class PayoutCandidateKafkaListener {
    private final PayoutCandidateUseCase payoutCandidateUseCase;

    // Kafka로 받는 길. 주문이 아직 Kafka로 보내지 않아도 미리 열어 둔다(#339)
    // 받는 타입은 정산 DTO. 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) 리스너마다 읽을 타입을 정한다
    // 그룹을 payout으로 정해 두는 이유: 기본값이 앱 이름이라 monolith에서는 다른 모듈과 같은 그룹이 되어 메시지가 한쪽에만 간다
    // Spring 이벤트와 Kafka로 같은 구매확정이 두 번 와도 서비스가 orderItemId로 중복을 막는다
    @KafkaListener(
            topics = PayoutTopics.ORDER_PRODUCT_CONFIRMED,
            groupId = PayoutTopics.CONSUMER_GROUP,
            properties = "spring.json.value.default.type=com.ok.payout.adapter.in.event.dto.OrderProductConfirmedMessage")
    void onKafka(OrderProductConfirmedMessage message) {
        PurchaseConfirmedCommand command = message.toCommand();
        // 값이 잘못된 메시지는 규칙(exception.md "다른 모듈이 보낸 값의 오류는 표준 예외")대로 IllegalArgumentException을 던진다.
        // Kafka는 예외가 나도 실패 기록이 쌓이는 곳이 없다. 기본 오류 처리기가 몇 번 다시 시도한 뒤 ERROR 로그를 남기고 다음 메시지로 넘어간다.
        // 그래서 멘토 지적(재처리해도 안 되는 이벤트로 실패가 쌓이지 않게)과 규칙을 함께 지킨다. 팀 DLT가 들어오면 이 예외로 원본이 DLT에 보관된다.
        // 확인은 서비스를 부르기 전에 입구에서 끝낸다. Spring 입구와 같은 검사 기준(PayoutInboundEventValidator)을 쓰기 위해서다.
        Optional<String> invalidReason = PayoutInboundEventValidator.findInvalidReason(command);
        if (invalidReason.isPresent()) {
            // 태그는 Spring 입구의 건너뛰기 로그와 같게 둬 한 번에 검색되게 한다. 로그는 오류 처리기가 남긴다(다시 시도마다 찍히지 않게 여기서 남기지 않음)
            throw new IllegalArgumentException("[PAYOUT_CANDIDATE_SKIPPED] 처리할 수 없는 구매확정 메시지입니다. reason="
                    + invalidReason.get() + ", command=" + command);
        }
        // DB 오류 같은 일시적 실패는 예외를 그대로 전파해 재시도 대상으로 남긴다.
        payoutCandidateUseCase.createFromPurchaseConfirmed(command);
    }
}
