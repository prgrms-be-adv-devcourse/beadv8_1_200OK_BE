package com.ok.member.member.application.port.out;

import java.time.LocalDateTime;

/**
 * outbox에 저장할 메시지 본문(JSON)을 만든다 (#587).
 * 메시지 모양(필드 이름)은 받는 모듈과의 약속이라 메시지 클래스가 있는 구현체({@code adapter.out.event})가 맡는다.
 * 서비스는 JSON 형식을 모른다.
 */
public interface MemberMessagePayloadWriter {

    String writeSellerOriginPostalCode(Long sellerId, String postalCode, LocalDateTime occurredAt);

}
