package com.ok.order.order.controller.dto;

import com.ok.order.order.service.dto.CreateOrderCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class CreateOrderRequestTest {

    @Test
    void 요청을_커맨드로_변환하면_모든_필드가_같은_값으로_옮겨진다() {
        // given: 문자열 필드가 많아서 서로 다른 값을 넣어 순서가 바뀌는 실수를 잡는다
        CreateOrderRequest sut = new CreateOrderRequest(
                "홍길동", "010-1234-5678", "06236", "서울시 강남구 테헤란로 1", "101호", true, 13_000L);

        // when
        CreateOrderCommand command = sut.toCommand(7L, "idem-key-1");

        // then
        assertThat(command).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(command.memberId()).isEqualTo(7L);
            softly.assertThat(command.idempotencyKey()).isEqualTo("idem-key-1");
            softly.assertThat(command.recipientName()).isEqualTo("홍길동");
            softly.assertThat(command.recipientPhone()).isEqualTo("010-1234-5678");
            softly.assertThat(command.zipCode()).isEqualTo("06236");
            softly.assertThat(command.address()).isEqualTo("서울시 강남구 테헤란로 1");
            softly.assertThat(command.addressDetail()).isEqualTo("101호");
            softly.assertThat(command.thirdPartyProvisionAgreed()).isTrue();
            softly.assertThat(command.expectedTotalAmount()).isEqualTo(13_000L);
        });
    }
}