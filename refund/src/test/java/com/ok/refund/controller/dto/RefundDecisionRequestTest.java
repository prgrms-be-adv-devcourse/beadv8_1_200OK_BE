package com.ok.refund.controller.dto;

import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    // 컨트롤러는 enum으로 받지 않아 허용 값을 문자열 패턴으로 따로 적었다. 결정이 늘었는데 패턴을 안 고치면 새 결정을 보낼 수 없고,
    // 패턴에만 있는 값은 toCommand의 valueOf에서 500이 된다. 그래서 두 목록이 같은지 확인한다
    @Test
    void 결정_패턴의_허용_값이_결정_종류와_같다() throws NoSuchFieldException {
        // given
        Pattern pattern = RefundDecisionRequest.class.getDeclaredField("decision").getAnnotation(Pattern.class);
        List<String> decisionNames = new ArrayList<>();
        for (RefundDecision decision : RefundDecision.values()) {
            decisionNames.add(decision.name());
        }

        // when
        List<String> allowed = List.of(pattern.regexp().split("\\|"));

        // then
        assertThat(allowed).containsExactlyInAnyOrderElementsOf(decisionNames);
    }

    @Test
    void 승인을_결정으로_옮긴다() {
        // when
        RefundDecisionCommand sut = new RefundDecisionRequest("APPROVE", null).toCommand(7L, 5L);

        // then
        assertThat(sut.decision()).isEqualTo(RefundDecision.APPROVE);
    }
}
