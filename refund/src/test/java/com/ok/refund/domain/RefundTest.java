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
        Refund sut = Refund.request(10L, 101L, 2, 1L, 2L, 55L, "감자", "1kg", 10_000L, "옵션을 잘못 골랐습니다.");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getOrderId()).isEqualTo(10L);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getQuantity()).isEqualTo(2);
            softly.assertThat(sut.getBuyerId()).isEqualTo(1L);
            softly.assertThat(sut.getSellerId()).isEqualTo(2L);
            softly.assertThat(sut.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(sut.getProductName()).isEqualTo("감자");
            softly.assertThat(sut.getOptionName()).isEqualTo("1kg");
            softly.assertThat(sut.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    @Test
    void 요청하면_배송비_세_칸은_0이고_돌려줄_금액은_상품_금액이다() {
        // when
        Refund sut = requested();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getShippingFeeAmount()).isZero();
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(sut.getRefundAmount()).isEqualTo(20_000L);
        });
    }

    @Test
    void 취소_사유는_없어도_된다() {
        // when
        Refund sut = Refund.request(10L, 101L, 1, 1L, 2L, 55L, "감자", "1kg", 10_000L, null);

        // then
        assertThat(sut.getCancelReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("일보다_작은_개수")
    void 개수가_1_미만이면_막는다(int quantity) {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, quantity, 1L, 2L, 55L, "감자", "1kg", 10_000L, null))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_QUANTITY));
    }

    static Stream<Integer> 일보다_작은_개수() {
        return Stream.of(0, -1);
    }

    @Test
    void 주문에서_받은_ID가_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, null, 55L, "감자", "1kg", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("주문·주문 상품·구매자·판매자·옵션 ID는 필수입니다.");
    }

    @Test
    void 주문에서_받은_이름이_비면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, null, "1kg", 10_000L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("상품명과 옵션명은 필수입니다.");
    }

    @Test
    void 단가가_음수면_표준_예외로_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 1, 1L, 2L, 55L, "감자", "1kg", -1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("단가는 0 이상이어야 합니다.");
    }

    @Test
    void 상품_금액이_long을_넘으면_막는다() {
        // when & then
        assertThatThrownBy(() -> Refund.request(10L, 101L, 2, 1L, 2L, 55L, "감자", "1kg", Long.MAX_VALUE, null))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void 승인하면_처리_중이_되고_진행_중_칸은_그대로_둔다() {
        // given
        Refund sut = requested();

        // when
        sut.approve(NOW, RefundShippingFee.none());

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
    void 승인하면_배송비_세_칸을_저장하고_처리_중이_된다() {
        // given
        Refund sut = requested();

        // when: 칸마다 다른 값을 넣어 칸이 뒤바뀌어 저장되지 않는지 본다
        sut.approve(NOW, new RefundShippingFee(3_000L, 2_000L, 1_000L, true));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isEqualTo(2_000L);
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isEqualTo(1_000L);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    // 돌려줄 금액 = 상품 금액 + 돌려줄 배송비 + 도서산간 운임 − 뺀 배송비
    @ParameterizedTest
    @MethodSource("배송비_판단과_돌려줄_금액")
    void 돌려줄_금액은_상품_금액에_배송비를_더하고_뺀_배송비를_뺀다(RefundShippingFee fee, long expected) {
        // given
        Refund sut = requested();
        sut.approve(NOW, fee);

        // when & then
        assertThat(sut.getRefundAmount()).isEqualTo(expected);
    }

    static Stream<Arguments> 배송비_판단과_돌려줄_금액() {
        return Stream.of(
                Arguments.of(RefundShippingFee.none(), 20_000L),
                Arguments.of(RefundShippingFee.refund(3_000L, 2_000L), 25_000L),
                Arguments.of(RefundShippingFee.deduct(3_000L), 17_000L),
                Arguments.of(RefundShippingFee.deduct(20_000L), 0L));
    }

    @Test
    void 받을_수_없는_판단으로_승인하면_예외를_던지고_바꾸지_않는다() {
        // given
        Refund refund = requested();

        // when & then
        assertThatThrownBy(() -> refund.approve(NOW, RefundShippingFee.notAllowed()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("받을 수 없는 배송비 판단으로는 승인할 수 없습니다.");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 정상이면 배송비 정책이 이런 판단을 "받을 수 없음"으로 돌려준다. 계산 버그로 여기 오면 커밋 뒤가 아니라 승인 전에 막아야
    // 트랜잭션이 되돌려져 처리 중에 갇히지 않고 요청됨으로 남는다 (지갑에 음수 금액을 요청하지 않게)
    @Test
    void 돌려줄_금액이_음수가_되는_판단으로_승인하면_예외를_던지고_바꾸지_않는다() {
        // given: 1,000원짜리 1개를 취소하는데 3,000원을 빼라는 판단 → 1,000 - 3,000 = -2,000원
        Refund refund = Refund.request(10L, 101L, 1, 1L, 2L, 55L, "감자", "1kg", 1_000L, null);

        // when & then
        assertThatThrownBy(() -> refund.approve(NOW, RefundShippingFee.deduct(3_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("돌려줄 금액이 음수가 되는 배송비 판단으로는 승인할 수 없습니다. amount=-2000");
        assertSoftly(softly -> {
            softly.assertThat(refund.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(refund.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(refund.getDecidedAt()).isNull();
        });
    }

    @Test
    void 요청됨이_아니면_배송비와_함께_승인해도_막고_배송비를_저장하지_않는다() {
        // given
        Refund refund = requested();
        refund.withdraw(NOW);

        // when & then
        assertThatThrownBy(() -> refund.approve(NOW, RefundShippingFee.refund(3_000L, 0)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        assertThat(refund.getShippingFeeAmount()).isZero();
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
    void 승인_뒤_거절하면_사유와_시각을_남기고_배송비를_0으로_되돌리고_진행_중_칸을_비운다() {
        // given: 배송비 세 칸에 서로 다른 값을 넣어 세 칸 모두 0이 되는지 본다
        Refund sut = requested();
        sut.approve(NOW, new RefundShippingFee(3_000L, 2_000L, 1_000L, true));
        LocalDateTime rejectedAt = NOW.plusMinutes(1);

        // when
        sut.rejectAfterApproval("주문이 취소 반영을 거절했습니다.", rejectedAt);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(sut.getRejectReason()).isEqualTo("주문이 취소 반영을 거절했습니다.");
            softly.assertThat(sut.getDecidedAt()).isEqualTo(rejectedAt);
            softly.assertThat(sut.getCompletedAt()).isNull();
            softly.assertThat(sut.getShippingFeeAmount()).isZero();
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    @ParameterizedTest
    @MethodSource("처리_중이_아닌_네_상태의_환불")
    void 처리_중이_아니면_승인_뒤_거절할_수_없다(Refund refund) {
        // when & then
        assertThatThrownBy(() -> refund.rejectAfterApproval("사유", NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    static Stream<Named<Refund>> 처리_중이_아닌_네_상태의_환불() {
        Refund rejected = requested();
        rejected.reject("사유", NOW);
        Refund withdrawn = requested();
        withdrawn.withdraw(NOW);
        Refund completed = requested();
        completed.approve(NOW, RefundShippingFee.none());
        completed.complete(NOW);
        // 이름을 붙여야 실패했을 때 어느 상태가 통과했는지 보인다
        return Stream.of(
                Named.of("요청됨", requested()),
                Named.of("거절", rejected),
                Named.of("철회", withdrawn),
                Named.of("완료", completed));
    }

    @ParameterizedTest
    @MethodSource("비어_있는_거절_사유")
    void 승인_뒤_거절은_사유가_필수다(String reason) {
        // given
        Refund refund = requested();
        refund.approve(NOW, RefundShippingFee.none());

        // when & then
        assertThatThrownBy(() -> refund.rejectAfterApproval(reason, NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REJECT_REASON_REQUIRED));
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);
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
                Named.of("처리 중", refund -> refund.approve(NOW, RefundShippingFee.none())),
                Named.of("거절", refund -> refund.reject("사유", NOW)),
                Named.of("철회", refund -> refund.withdraw(NOW)));
        List<Named<Consumer<Refund>>> actions = List.of(
                Named.of("승인", refund -> refund.approve(NOW, RefundShippingFee.none())),
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
    void 처리_중이면_멈춘_단계와_실패_이유를_기록하고_상태는_그대로다() {
        // given
        Refund sut = requested();
        sut.approve(NOW, RefundShippingFee.none());

        // when
        sut.recordFailure(RefundFailedStep.WALLET, "WALLET_API_FAILED");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundFailedStep.WALLET);
            softly.assertThat(sut.getFailureCode()).isEqualTo("WALLET_API_FAILED");
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
        });
    }

    @Test
    void 요청하면_실패_기록은_비어_있다() {
        // when
        Refund sut = requested();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getFailedStep()).isNull();
            softly.assertThat(sut.getFailureCode()).isNull();
        });
    }

    // 실패 기록은 다른 모듈을 부르는 처리 중에만 생긴다. 요청됨·거절·철회에 기록하려는 건 상태가 맞지 않는 요청이다
    @ParameterizedTest
    @MethodSource("처리_중이_아닌_요청")
    void 처리_중이_아니면_실패를_기록하지_않는다(Consumer<Refund> toState) {
        // given
        Refund refund = requested();
        toState.accept(refund);

        // when & then
        assertThatThrownBy(() -> refund.recordFailure(RefundFailedStep.WALLET, "WALLET_API_FAILED"))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
        assertThat(refund.getFailedStep()).isNull();
    }

    static Stream<Named<Consumer<Refund>>> 처리_중이_아닌_요청() {
        return Stream.of(
                Named.of("요청됨", refund -> {
                }),
                Named.of("거절", refund -> refund.reject("사유", NOW)),
                Named.of("철회", refund -> refund.withdraw(NOW)));
    }

    @ParameterizedTest
    @MethodSource("비어_있는_실패_정보")
    void 실패_단계나_이유가_비면_표준_예외로_막는다(RefundFailedStep step, String failureCode) {
        // given
        Refund refund = requested();
        refund.approve(NOW, RefundShippingFee.none());

        // when & then
        assertThatThrownBy(() -> refund.recordFailure(step, failureCode))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("실패 단계와 이유는 필수입니다.");
    }

    static Stream<Arguments> 비어_있는_실패_정보() {
        return Stream.of(
                Arguments.of(null, "WALLET_API_FAILED"),
                Arguments.of(RefundFailedStep.WALLET, null),
                Arguments.of(RefundFailedStep.WALLET, " "));
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

    // ID와 생성 시각은 저장해야 생긴다. 저장소가 저장 뒤 채운 새 객체를 돌려주므로(#481) 요청 때는 비어 있어야 한다
    @Test
    void 요청은_저장_전이라_ID와_생성_시각이_비어_있다() {
        // when
        Refund sut = requested();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isNull();
            softly.assertThat(sut.getCreatedAt()).isNull();
        });
    }

    // 저장된 값은 요청 때 검사를 거친 값이라 그대로 되살린다. 칸 순서가 뒤바뀌지 않았는지 칸마다 다른 값을 넣어 본다
    @Test
    void 복원하면_저장된_칸을_모두_그대로_가진다() {
        // given
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 3, 9, 0);
        LocalDateTime completedAt = LocalDateTime.of(2026, 11, 4, 10, 5);

        // when
        Refund sut = Refund.restore(7L, 10L, 101L, 2, 1L, 2L, 55L, "감자", "1kg", 10_000L, 20_000L, 3_000L, 2_000L, 1_000L,
                "옵션을 잘못 골랐습니다.", RefundStatus.PROCESSING, null, NOW, completedAt, RefundFailedStep.ORDER, "ORDER_API_FAILED",
                101L, createdAt);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isEqualTo(7L);
            softly.assertThat(sut.getOrderId()).isEqualTo(10L);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getQuantity()).isEqualTo(2);
            softly.assertThat(sut.getBuyerId()).isEqualTo(1L);
            softly.assertThat(sut.getSellerId()).isEqualTo(2L);
            softly.assertThat(sut.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(sut.getProductName()).isEqualTo("감자");
            softly.assertThat(sut.getOptionName()).isEqualTo("1kg");
            softly.assertThat(sut.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(sut.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(sut.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isEqualTo(2_000L);
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isEqualTo(1_000L);
            softly.assertThat(sut.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getRejectReason()).isNull();
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(completedAt);
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundFailedStep.ORDER);
            softly.assertThat(sut.getFailureCode()).isEqualTo("ORDER_API_FAILED");
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(101L);
            softly.assertThat(sut.getCreatedAt()).isEqualTo(createdAt);
        });
    }

    // ID와 상태는 DB에서 NOT NULL이라 비었으면 저장된 행이 아니다. 부르는 쪽 버그라 표준 예외로 막는다
    @Test
    void ID_없이_복원하면_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> Refund.restore(null, 10L, 101L, 2, 1L, 2L, 55L, "감자", "1kg", 10_000L, 20_000L, 0L, 0L, 0L,
                null, RefundStatus.REQUESTED, null, null, null, null, null, 101L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("저장된 환불을 되살리려면 ID와 상태가 필요합니다.");
    }

    // 완료(#414): 승인 뒤 단계를 모두 마친 처리 중 환불만 끝낸다. 진행 중 칸을 비워 같은 상품의 남은 개수를 다시 요청할 수 있게 한다
    @Test
    void 처리_중을_완료하면_완료가_되고_완료_시각이_남고_진행_중_칸이_비워진다() {
        // given
        Refund sut = requested();
        sut.approve(NOW, RefundShippingFee.none());
        LocalDateTime completedAt = NOW.plusMinutes(5);

        // when
        sut.complete(completedAt);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(completedAt);
        });
    }

    // 요청됨에서 바로 완료하면 승인(배송비 판단) 없이 끝나 돈 계산이 비고, 이미 끝난 환불을 다시 완료하면 단계가 두 번 돈 것이다
    @ParameterizedTest
    @MethodSource("처리_중이_아닌_환불")
    void 처리_중이_아니면_완료할_수_없다(Refund refund) {
        // when & then
        assertThatThrownBy(() -> refund.complete(NOW))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.INVALID_REFUND_STATE));
    }

    static Stream<Refund> 처리_중이_아닌_환불() {
        Refund completed = requested();
        completed.approve(NOW, RefundShippingFee.none());
        completed.complete(NOW);
        Refund withdrawn = requested();
        withdrawn.withdraw(NOW);
        return Stream.of(requested(), completed, withdrawn);
    }

    private static Refund requested() {
        return Refund.request(10L, 101L, 2, 1L, 2L, 55L, "감자", "1kg", 10_000L, null);
    }
}
