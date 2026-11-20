package com.ok.delivery.adapter.in.event.dto;

import com.ok.delivery.application.dto.SellerOriginPostalCodeCommand;

import java.time.LocalDateTime;

/**
 * member가 보내는 판매자 출고지 우편번호 메시지. 이 필드 이름·타입이 member와의 약속이다.
 * 메시지에 보내는 쪽 클래스 이름이 없어서(타입 헤더 없음) JSON 모양으로 읽는다. 필드를 바꾸면 member도 같이 바꿔야 한다.
 *
 * @param sellerId   판매자 ID. 메시지 키와 같은 값이다
 * @param postalCode 출고지 우편번호(숫자 5자리). {@code null}이면 출고지를 지운 것이다. member는 지금 항상 5자리 숫자만 보내고,
 *                   빈 문자열이나 공백은 값이 틀린 메시지라 재시도 없이 DLT로 간다
 * @param occurredAt member에서 값이 바뀐 시각
 */
public record SellerOriginPostalCodeMessage(Long sellerId, String postalCode, LocalDateTime occurredAt) {

    public SellerOriginPostalCodeCommand toCommand() {
        return new SellerOriginPostalCodeCommand(sellerId, postalCode, occurredAt);
    }
}
