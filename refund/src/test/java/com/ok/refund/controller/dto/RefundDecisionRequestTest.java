package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundDecisionRequestTest {

    @ParameterizedTest
    @MethodSource("적지_않은_것으로_보는_사유")
    void 거절_사유가_비었거나_공백뿐이면_비운다(String rejectReason) {
        // when
        RefundDecisionRequest sut = new RefundDecisionRequest("REJECT", rejectReason);

        // then
        assertThat(sut.rejectReason()).isNull();
    }

    static Stream<String> 적지_않은_것으로_보는_사유() {
        return Arrays.stream(new String[]{null, "", "   ", "\t\n"});
    }

    @Test
    void 거절_사유의_앞뒤_공백을_지운다() {
        // when
        RefundDecisionRequest sut = new RefundDecisionRequest("REJECT", "  이미 포장을 마쳤습니다.\n");

        // then
        assertThat(sut.rejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
    }

    @Test
    void 명령으로_바꿀_때_경로의_환불_ID와_토큰의_판매자_ID를_순서대로_옮긴다() {
        // given: 같은 Long 칸이 둘이라 서로 다른 값을 넣어 뒤바뀜을 잡는다
        RefundDecisionRequest request = new RefundDecisionRequest("REJECT", "이미 포장을 마쳤습니다.");

        // when
        RefundDecisionCommand sut = request.toCommand(7L, 5L);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.refundId()).isEqualTo(7L);
            softly.assertThat(sut.sellerId()).isEqualTo(5L);
            softly.assertThat(sut.decision()).isEqualTo(RefundDecision.REJECT);
            softly.assertThat(sut.rejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
        });
    }

    @Test
    void 승인을_결정으로_옮긴다() {
        // when
        RefundDecisionCommand sut = new RefundDecisionRequest("APPROVE", null).toCommand(7L, 5L);

        // then
        assertThat(sut.decision()).isEqualTo(RefundDecision.APPROVE);
    }
}
