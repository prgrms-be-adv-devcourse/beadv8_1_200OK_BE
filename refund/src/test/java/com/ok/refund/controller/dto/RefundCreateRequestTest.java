package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundRequestCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundCreateRequestTest {

    @ParameterizedTest
    @MethodSource("적지_않은_것으로_보는_사유")
    void 취소_사유가_비었거나_공백뿐이면_비운다(String cancelReason) {
        // when
        RefundCreateRequest sut = new RefundCreateRequest(10L, 101L, 1, cancelReason);

        // then
        assertThat(sut.cancelReason()).isNull();
    }

    static Stream<String> 적지_않은_것으로_보는_사유() {
        return Arrays.stream(new String[]{null, "", "   ", "\t\n"});
    }

    @Test
    void 취소_사유의_앞뒤_공백을_지운다() {
        // when
        RefundCreateRequest sut = new RefundCreateRequest(10L, 101L, 1, "  옵션을 잘못 골랐습니다.\n");

        // then
        assertThat(sut.cancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
    }

    @Test
    void 명령으로_바꿀_때_구매자_ID는_토큰_값을_쓰고_나머지_칸을_순서대로_옮긴다() {
        // given: 같은 Long 칸이 셋이라 서로 다른 값을 넣어 뒤바뀜을 잡는다
        RefundCreateRequest request = new RefundCreateRequest(10L, 101L, 2, "옵션을 잘못 골랐습니다.");

        // when
        RefundRequestCommand sut = request.toCommand(1L);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.buyerId()).isEqualTo(1L);
            softly.assertThat(sut.orderId()).isEqualTo(10L);
            softly.assertThat(sut.orderItemId()).isEqualTo(101L);
            softly.assertThat(sut.quantity()).isEqualTo(2);
            softly.assertThat(sut.cancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
        });
    }
}
