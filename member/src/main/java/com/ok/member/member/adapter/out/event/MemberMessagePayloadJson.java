package com.ok.member.member.adapter.out.event;

import com.ok.member.member.application.port.out.MemberMessagePayloadWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

/**
 * outbox에 저장하는 메시지 본문을 JSON으로 쓰고 읽는다 (#587).
 * 쓰는 쪽(outbox 기록)과 읽는 쪽(Kafka 발송)이 같은 메시지 클래스로 변환하도록 한곳에 둔다.
 * 실제로 Kafka에 나가는 JSON은 읽어 낸 메시지 객체를 Kafka 공통 직렬화 설정(common-kafka.yaml)으로 다시 쓴 것이다.
 * 그래서 시각 같은 값의 모양은 다른 모듈의 메시지와 같아진다.
 */
@Component
@RequiredArgsConstructor
class MemberMessagePayloadJson implements MemberMessagePayloadWriter {

    private final JsonMapper jsonMapper;

    @Override
    public String writeSellerOriginPostalCode(Long sellerId, String postalCode, LocalDateTime occurredAt) {
        return jsonMapper.writeValueAsString(new SellerOriginPostalCodeMessage(sellerId, postalCode, occurredAt));
    }

    /**
     * 저장해 둔 본문을 메시지로 읽는다. 시스템이 쓴 값이라 읽지 못하면 버그로 보고 표준 예외를 던진다.
     */
    SellerOriginPostalCodeMessage readSellerOriginPostalCode(String payload) {
        try {
            return jsonMapper.readValue(payload, SellerOriginPostalCodeMessage.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("출고지 우편번호 outbox 본문을 읽을 수 없습니다.", e);
        }
    }
}
