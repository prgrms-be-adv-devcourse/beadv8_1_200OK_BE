package com.ok.payout.service;

import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.repository.PayoutQueryRepository.DraftItemRow;
import com.ok.payout.repository.PayoutQueryRepository.FailureRow;
import com.ok.payout.repository.PayoutQueryRepository.SellerTotalRow;
import com.ok.payout.repository.PayoutQueryRepository.SnapshotItemRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 결과 검증 판정만 확인하는 단위 테스트 (DB·Spring 없음, #290)
// 기본 데이터: 주문 1(판매자 10: 대금 1번 9,700 + 수수료 2번 300 + 배송비 3번 3,000), 주문 2(판매자 20: 대금 4번 19,400)
// 검증 규칙을 추가하면 여기에 통과·실패 케이스를 함께 추가
class PayoutDraftValidatorTest {

    private static final SnapshotItemRow AMOUNT_1 = snapshot(1L, 1L, 10L, PayoutEventType.SALE_AMOUNT, 9_700L);
    private static final SnapshotItemRow FEE_2 = snapshot(2L, 1L, 10L, PayoutEventType.SALE_FEE, 300L);
    private static final SnapshotItemRow SHIPPING_3 = snapshot(3L, 1L, 10L, PayoutEventType.SALE_SHIPPING_FEE, 3_000L);
    private static final SnapshotItemRow AMOUNT_4 = snapshot(4L, 2L, 20L, PayoutEventType.SALE_AMOUNT, 19_400L);

    // 위 스냅샷 후보가 제대로 옮겨진 draft 항목
    private static final DraftItemRow DRAFT_1 = sellerDraft(1L, 1L, 10L, 10L, PayoutEventType.SALE_AMOUNT, 9_700L);
    private static final DraftItemRow DRAFT_2 = systemDraft(2L, 1L, 10L, PayoutEventType.SALE_FEE, 300L);
    private static final DraftItemRow DRAFT_3 = sellerDraft(3L, 1L, 10L, 10L, PayoutEventType.SALE_SHIPPING_FEE, 3_000L);
    private static final DraftItemRow DRAFT_4 = sellerDraft(4L, 2L, 20L, 20L, PayoutEventType.SALE_AMOUNT, 19_400L);

    private static final List<SnapshotItemRow> SNAPSHOT = List.of(AMOUNT_1, FEE_2, SHIPPING_3, AMOUNT_4);

    // ---------- 후보 단위 검사 ----------

    @Test
    void 스냅샷_후보가_모두_draft에_맞게_들어가면_걸리는_주문이_없다() {
        // given
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_3, DRAFT_4), List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderMismatches()).isEmpty();
            softly.assertThat(sut.failedCandidatesInDraft()).isEmpty();
        });
    }

    @Test
    void 입력_검증_실패로_draft에_없는_후보는_정상이다() {
        // given: 주문 2의 후보 4번은 validate에서 차감 기록 없음으로 빠짐
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_3),
                List.of(new FailureRow(4L, 2L, PayoutFailureReason.DEDUCTION_MISSING)));

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches()).isEmpty();
    }

    @Test
    void draft에서_빠진_후보가_있으면_그_주문이_걸린다() {
        // given: 주문 1의 배송비 3번이 draft에 없음 (실패 기록도 없음)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_4), List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then: 주문 2는 정상이라 걸리지 않음
        assertThat(sut.orderMismatches())
                .containsExactly(new PayoutDraftValidator.OrderMismatch(1L, Map.of(3L, "draft 누락")));
    }

    @Test
    void 금액이_다르게_옮겨진_후보가_있으면_그_주문이_걸린다() {
        // given: 대금 1번이 9,000원으로 들어감
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(sellerDraft(1L, 1L, 10L, 10L, PayoutEventType.SALE_AMOUNT, 9_000L), DRAFT_2, DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches())
                .containsExactly(new PayoutDraftValidator.OrderMismatch(1L, Map.of(1L, "금액 다름(스냅샷 9700, draft 9000)")));
    }

    @Test
    void 스냅샷에_없는_후보가_draft에_들어가면_그_항목의_주문이_걸린다() {
        // given: 스냅샷에 없는 후보 9번(주문 3)이 시스템 몫에 들어감
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_3, DRAFT_4, systemDraft(9L, 3L, 30L, PayoutEventType.SALE_FEE, 500L)),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches())
                .containsExactly(new PayoutDraftValidator.OrderMismatch(3L, Map.of(9L, "스냅샷에 없는 항목")));
    }

    @Test
    void 한_후보가_판매자_몫과_시스템_몫에_모두_들어가면_그_주문이_걸린다() {
        // given: 대금 1번이 시스템 몫에도 들어감
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, systemDraft(1L, 1L, 10L, PayoutEventType.SALE_AMOUNT, 9_700L), DRAFT_2, DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then: 중복과, 시스템 몫 쪽 항목의 몫 구분 오류가 함께 남음
        assertThat(sut.orderMismatches()).containsExactly(new PayoutDraftValidator.OrderMismatch(1L,
                Map.of(1L, "판매자 몫·시스템 몫에 중복, 판매자 몫이 시스템 몫에 들어감")));
    }

    @Test
    void 수수료가_판매자_몫에_들어가면_그_주문이_걸린다() {
        // given: 수수료 2번이 판매자 10의 정산에 묶임 (판매자에게 수수료까지 지급됨)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, sellerDraft(2L, 1L, 10L, 10L, PayoutEventType.SALE_FEE, 300L), DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches())
                .containsExactly(new PayoutDraftValidator.OrderMismatch(1L, Map.of(2L, "수수료가 판매자 몫에 들어감")));
    }

    @Test
    void 다른_판매자_정산에_묶인_항목이_있으면_금액이_같아도_그_주문이_걸린다() {
        // given: 판매자 10의 대금 1번이 판매자 20의 헤더 아래에 묶임 (돈이 판매자 20에게 감)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(sellerDraft(1L, 1L, 10L, 20L, PayoutEventType.SALE_AMOUNT, 9_700L), DRAFT_2, DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches())
                .containsExactly(new PayoutDraftValidator.OrderMismatch(1L, Map.of(1L, "다른 판매자 정산에 묶임(후보 10, 헤더 20)")));
    }

    @Test
    void draft_항목의_판매자가_후보와_다르면_그_주문이_걸린다() {
        // given: 대금 1번이 판매자 20으로 복사됨 (헤더도 20)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(sellerDraft(1L, 1L, 20L, 20L, PayoutEventType.SALE_AMOUNT, 9_700L), DRAFT_2, DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches()).containsExactly(new PayoutDraftValidator.OrderMismatch(1L,
                Map.of(1L, "판매자 다름(후보 10, draft 20), 다른 판매자 정산에 묶임(후보 10, 헤더 20)")));
    }

    @Test
    void draft_항목의_주문이_후보와_다르면_두_주문이_모두_걸린다() {
        // given: 대금 1번이 주문 2로 복사됨 → commitStep은 draft 항목의 주문으로 거르므로 주문 2도 빠져야 함
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(sellerDraft(1L, 2L, 10L, 10L, PayoutEventType.SALE_AMOUNT, 9_700L), DRAFT_2, DRAFT_3, DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches()).containsExactly(
                new PayoutDraftValidator.OrderMismatch(1L, Map.of(1L, "주문 다름(스냅샷 1, draft 2)")),
                new PayoutDraftValidator.OrderMismatch(2L, Map.of(1L, "주문 다름(스냅샷 1, draft 2)")));
    }

    @Test
    void 타입이_다르게_옮겨지면_그_주문이_걸린다() {
        // given: 배송비 3번이 대금 타입으로 복사됨
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, sellerDraft(3L, 1L, 10L, 10L, PayoutEventType.SALE_AMOUNT, 3_000L), DRAFT_4),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches()).containsExactly(new PayoutDraftValidator.OrderMismatch(1L,
                Map.of(3L, "타입 다름(후보 SALE_SHIPPING_FEE, draft SALE_AMOUNT)")));
    }

    @Test
    void 실패_기록이_있는_후보가_draft에_들어가면_코드_고장으로_따로_돌려준다() {
        // given: 후보 4번은 validate에서 실패로 기록됐는데 draft에도 들어감 (draft 대상 조회 조건 버그)
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_3, DRAFT_4),
                List.of(new FailureRow(4L, 2L, PayoutFailureReason.DEDUCTION_MISSING)));

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then: 주문으로 격리하지 않고 코드 고장으로 분류 (이 후보에는 결과 검증 실패를 따로 남길 수 없음)
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.failedCandidatesInDraft()).containsExactly(4L);
            softly.assertThat(sut.orderMismatches()).isEmpty();
        });
    }

    @Test
    void 스냅샷_밖_후보라도_실패_기록이_있는데_draft에_들어가면_코드_고장이다() {
        // given: 스냅샷에 없는 후보 9번이 실패 기록도 있는데 draft에 들어감
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_3, DRAFT_4, systemDraft(9L, 3L, 30L, PayoutEventType.SALE_FEE, 500L)),
                List.of(new FailureRow(9L, 3L, PayoutFailureReason.UNEXPECTED_ERROR)));

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.failedCandidatesInDraft()).containsExactly(9L);
    }

    @Test
    void 이미_결과_검증으로_빠진_주문은_다시_검사하지_않는다() {
        // given: 같은 스냅샷을 다시 검증 (주문 1은 이전 검증에서 배송비 누락으로 빠지고 기록됨)
        // 주문 1의 후보는 draft에도 있고 VERIFY_MISMATCH 기록도 있지만, 실패 후보가 draft에 섞인 것으로 보지 않음
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, DRAFT_4),
                List.of(new FailureRow(1L, 1L, PayoutFailureReason.VERIFY_MISMATCH),
                        new FailureRow(2L, 1L, PayoutFailureReason.VERIFY_MISMATCH),
                        new FailureRow(3L, 1L, PayoutFailureReason.VERIFY_MISMATCH)));

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then: 새로 걸리는 것이 없음
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderMismatches()).isEmpty();
            softly.assertThat(sut.failedCandidatesInDraft()).isEmpty();
        });
    }

    @Test
    void 여러_주문이_걸리면_주문마다_따로_돌려준다() {
        // given: 주문 1의 배송비 누락, 주문 2의 대금 금액 다름
        PayoutDraftValidator.Input input = new PayoutDraftValidator.Input(SNAPSHOT,
                List.of(DRAFT_1, DRAFT_2, sellerDraft(4L, 2L, 20L, 20L, PayoutEventType.SALE_AMOUNT, 1L)),
                List.of());

        // when
        PayoutDraftValidator.Result sut = PayoutDraftValidator.validate(input);

        // then
        assertThat(sut.orderMismatches()).containsExactly(
                new PayoutDraftValidator.OrderMismatch(1L, Map.of(3L, "draft 누락")),
                new PayoutDraftValidator.OrderMismatch(2L, Map.of(4L, "금액 다름(스냅샷 19400, draft 1)")));
    }

    // ---------- 판매자 합계 검사 ----------

    @Test
    void 판매자_헤더_금액이_항목_합계와_같으면_불일치가_없다() {
        // given
        List<SellerTotalRow> totals = List.of(new SellerTotalRow(10L, 12_700L, 12_700L), new SellerTotalRow(20L, 19_400L, 19_400L));

        // when
        List<String> sut = PayoutDraftValidator.checkSellerTotals(totals);

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 판매자_헤더_금액이_항목_합계와_다르면_그_판매자를_돌려준다() {
        // given: 판매자 20의 헤더만 틀림
        List<SellerTotalRow> totals = List.of(new SellerTotalRow(10L, 12_700L, 12_700L), new SellerTotalRow(20L, 19_000L, 19_400L));

        // when
        List<String> sut = PayoutDraftValidator.checkSellerTotals(totals);

        // then
        assertThat(sut).containsExactly("판매자 20: 헤더 19000, 항목 합계 19400");
    }

    private static SnapshotItemRow snapshot(Long candidateId, Long orderId, Long sellerId, PayoutEventType eventType,
                                            Long amount) {
        return new SnapshotItemRow(candidateId, orderId, sellerId, eventType, amount);
    }

    private static DraftItemRow sellerDraft(Long candidateId, Long orderId, Long sellerId, Long headerSellerId,
                                            PayoutEventType eventType, Long amount) {
        return new DraftItemRow(candidateId, orderId, sellerId, headerSellerId, eventType, amount, false);
    }

    private static DraftItemRow systemDraft(Long candidateId, Long orderId, Long sellerId, PayoutEventType eventType,
                                            Long amount) {
        return new DraftItemRow(candidateId, orderId, sellerId, null, eventType, amount, true);
    }
}
