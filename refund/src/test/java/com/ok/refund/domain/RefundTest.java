package com.ok.refund.domain;

import com.ok.common.exception.RestApiException;
import com.ok.refund.exception.RefundErrorCode;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 10, 0);

    @Test
    void 요청하면_요청됨으로_만들고_상품_금액을_계산하고_진행_중_칸을_채운다() {
        // when
        Refund sut = Refund.request(10L, 101L, 2, 1L, 2L, 55L, "양말", "흰색", 10_000L, "옵션을 잘못 골랐습니다.");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getOrderId()).isEqualTo(10L);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getQuantity()).isEqualTo(2);
            softly.assertThat(sut.getBuyerId()).isEqualTo(1L);
            softly.assertThat(sut.getSellerId()).isEqualTo(2L);
            softly.assertThat(sut.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(sut.getProductName()).isEqualTo("양말");
            softly.assertThat(sut.getOptionName()).isEqualTo("흰색");
            softly.assertThat(sut.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    @Test
    void 취소_사유는_없어도_된다() {
        // when
        Refund sut = Refund.request(10L, 101L, 1, 1L, 2L, 55L, "양말", "흰색", 10_000L, null);

        // then
        assertThat(sut.getCancelReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("일보다_작은_개수")
    void 개수가_1_미만이면_막는다(int quantity) {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, quantity, 1L, 2L, 55L, "양말", "흰색", 10_000L, null))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_QUANTITY));
    }

    static Stream<Integer> 일보다_작은_개수() {
        return Stream.of(0, -1);
    }

    @Test
    void 주문에서_받은_ID가_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, null, 55L, "양말", "흰색", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문·주문 상품·구매자·판매자·옵션 ID는 필수입니다.");
    }

    @Test
    void 주문에서_받은_이름이_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, null, "흰색", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품명과 옵션명은 필수입니다.");
    }

    @Test
    void 단가가_음수면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, "양말", "흰색", -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("단가는 0 이상이어야 합니다.");
    }

    @Test
    void 상품_금액이_long을_넘으면_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 2, 1L, 2L, 55L, "양말", "흰색", Long.MAX_VALUE, null))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 승인하면_처리_중이_되고_진행_중_칸은_그대로_둔다() {
        // given
        Refund sut = requested();

        // when
        sut.approve(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getRejectReason()).isNull();
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
        });
    }

    @Test
    void 거절하면_거절이_되고_사유를_남기고_진행_중_칸을_비운다() {
        // given
        Refund sut = requested();

        // when
        sut.reject("이미 포장을 마쳤습니다.", NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(sut.getRejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("비어_있는_거절_사유")
    void 거절_사유가_비면_막는다(String reason) {
        // given
        Refund refund = requested();

        // when & then
        assertThatThrownBy(() -> refund.reject(reason, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REJECT_REASON_REQUIRED));
    }

    static Stream<String> 비어_있는_거절_사유() {
        return Stream.of(null, "", "  ");
    }

    @Test
    void 철회하면_철회가_되고_진행_중_칸을_비운다() {
        // given
        Refund sut = requested();

        // when
        sut.withdraw(NOW);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.WITHDRAWN);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
            softly.assertThat(sut.getRejectReason()).isNull();
        });
    }

    // 승인·거절·철회는 모두 요청됨에서만 된다. 이미 결정된 세 상태에서 세 동작을 모두 막는지 본다
    @ParameterizedTest
    @MethodSource("결정된_요청과_동작")
    void 요청됨이_아니면_승인_거절_철회를_막는다(Consumer<Refund> decided, Consumer<Refund> action) {
        // given
        Refund refund = requested();
        decided.accept(refund);

        // when & then
        assertThatThrownBy(() -> action.accept(refund))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    static Stream<Arguments> 결정된_요청과_동작() {
        // 이름을 붙여야 실패했을 때 어느 상태에서 어느 동작이 통과했는지 보인다
        List<Named<Consumer<Refund>>> decisions = List.of(
                Named.of("처리 중", refund -> refund.approve(NOW)),
                Named.of("거절", refund -> refund.reject("사유", NOW)),
                Named.of("철회", refund -> refund.withdraw(NOW)));
        List<Named<Consumer<Refund>>> actions = List.of(
                Named.of("승인", refund -> refund.approve(NOW)),
                Named.of("거절", refund -> refund.reject("사유", NOW)),
                Named.of("철회", refund -> refund.withdraw(NOW)));
        List<Arguments> cases = new ArrayList<>();
        for (Named<Consumer<Refund>> decision : decisions) {
            for (Named<Consumer<Refund>> action : actions) {
                cases.add(Arguments.of(decision, action));
            }
        }
        return cases.stream();
    }

    // 사유 검사가 상태 검사보다 먼저다. 이미 끝난 요청이어도 사유가 비면 상태 충돌(409)이 아니라 사유 없음(422)으로 막는다
    @Test
    void 이미_끝난_요청에_사유_없이_거절하면_사유_없음으로_막는다() {
        // given
        Refund refund = requested();
        refund.withdraw(NOW);

        // when & then
        assertThatThrownBy(() -> refund.reject(" ", NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REJECT_REASON_REQUIRED));
    }

    @Test
    void 구매자와_판매자_본인인지_확인한다() {
        // given
        Refund sut = requested();

        // when & then
        assertSoftly(softly -> {
            softly.assertThat(sut.isOwnedByBuyer(1L)).isTrue();
            softly.assertThat(sut.isOwnedByBuyer(2L)).isFalse();
            softly.assertThat(sut.isOwnedBySeller(2L)).isTrue();
            softly.assertThat(sut.isOwnedBySeller(1L)).isFalse();
        });
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, 1L, 2L, 55L, "양말", "흰색", 10_000L, null);
    }
}
