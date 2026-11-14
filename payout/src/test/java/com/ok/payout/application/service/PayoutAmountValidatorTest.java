package com.ok.payout.application.service;

import com.ok.payout.application.port.out.OverchargeCandidateRow;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutOverchargeEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 금액 검사 판정만 확인하는 단위 테스트 (DB·Spring 없음)
// 조회 조건(스냅샷 밖 후보 포함, 조정 후보 제외 등)은 쿼리가 맡으므로 PayoutSettleIntegrationTest에서 확인
class PayoutAmountValidatorTest {

    private static final Long SNAPSHOT_ID = 12L;
    private static final long ORDER_ID = 100L;
    private static final long OTHER_ORDER_ID = 200L;

    // 후보 ID 발급용 (테스트마다 새 인스턴스라 필드로 둠)
    private long nextCandidateId = 1L;

    // 판정에 넘길 값 (테스트마다 채움)
    private final List<PayoutCandidateItem> targets = new ArrayList<>();
    private final Map<Long, Long> totals = new HashMap<>();
    private final Map<Long, Long> deductions = new HashMap<>();
    private final List<PayoutCandidateItem> originals = new ArrayList<>();
    // 초과 청구 근거용 (#391): 초과 청구 주문의 후보 전체(생성 순서)와 지갑 금액 충돌
    private final List<OverchargeCandidateRow> overchargeRows = new ArrayList<>();
    private final Map<Long, List<Long>> conflicts = new HashMap<>();
    // 근거 행의 생성 시각 (넣은 순서대로 1분씩 늘려 생성 순서를 정함)
    private LocalDateTime nextCreatedAt = LocalDateTime.of(2026, 11, 1, 10, 0);

    // ---------- 초과 청구 ----------

    @Test
    void 주문_후보_합계가_차감액과_같으면_통과한다() {
        // given: 대금 9,700 + 수수료 300 + 배송비 3,000 = 13,000 = 차감액
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        targets.add(shippingFee(ORDER_ID, 10L, 3_000L));
        givenOrder(ORDER_ID, 13_000L, 13_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 주문_후보_합계가_차감액보다_작으면_통과한다() {
        // given: 후보 합계 10,000 < 차감액 12,500 (차감액에는 있지만 정산후보로 오지 않는 금액이 있는 경우)
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        givenOrder(ORDER_ID, 10_000L, 12_500L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 주문_후보_합계가_차감액보다_1원이라도_많으면_이번_스냅샷의_그_주문_후보_전부_금액_불일치다() {
        // given: 판매자 10의 대금·수수료·배송비와 판매자 20의 대금, 합계 13,001 > 차감액 13,000
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        targets.add(shippingFee(ORDER_ID, 10L, 3_000L));
        targets.add(saleAmount(ORDER_ID, 1002L, 20L, 1L));
        givenOrder(ORDER_ID, 13_001L, 13_000L);
        givenOverchargeRowsFromTargets();

        // when
        List<PayoutFailure> sut = validate();

        // then: 어느 주문상품이 틀렸는지 모르므로 다른 판매자 후보까지 전부 같은 사유·상세
        //   마지막에 들어온 1002의 1원이 누적합을 넘겼고, 혼자 넘는 주문상품은 없음
        String detail = "초과 청구: 결제 13000, 후보 합계 13001, 넘친 금액 1, 넘게 만든 주문상품 1002, 이미 지급 0, "
                + "혼자 넘는 주문상품 없음, 판매자 2명, 지갑 금액 충돌 없음";
        assertThat(sut)
                .extracting(PayoutFailure::getSellerId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(20L, PayoutFailureReason.AMOUNT_MISMATCH, detail));
    }

    @Test
    void 초과_청구에_걸린_주문은_수수료_검사를_하지_않아_후보마다_실패가_1건이다() {
        // given: 수수료도 틀리고(300이어야 하는데 500) 합계도 차감액을 넘음
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 500L);
        givenOrder(ORDER_ID, 10_200L, 10_000L);
        givenOverchargeRowsFromTargets();

        // when
        List<PayoutFailure> sut = validate();

        // then: 초과 청구 상세만 있고(FEE_MISMATCH 없음) 후보 2건에 1건씩
        String detail = "초과 청구: 결제 10000, 후보 합계 10200, 넘친 금액 200, 넘게 만든 주문상품 1001, 이미 지급 0, "
                + "혼자 넘는 주문상품 1001, 판매자 1명, 지갑 금액 충돌 없음";
        assertThat(sut)
                .extracting(PayoutFailure::getCandidateId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(1L, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(2L, PayoutFailureReason.AMOUNT_MISMATCH, detail));
    }

    // ---------- 초과 청구 근거 (#391) ----------

    @Test
    void 초과_청구_상세에_넘친_금액_넘게_만든_주문상품_이미_지급한_금액을_남긴다() {
        // given: 결제 30,000. 주문상품 101(30,000)은 지난 배치에 지급됨, 이번에 주문상품 102(15,000)가 들어옴
        PayoutCandidateItem paid = saleAmount(ORDER_ID, 101L, 10L, 30_000L);
        PayoutCandidateItem current = saleAmount(ORDER_ID, 102L, 10L, 15_000L);
        targets.add(current);
        givenOrder(ORDER_ID, 45_000L, 30_000L);
        overchargeRows.add(row(paid, true));
        overchargeRows.add(row(current, false));

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut)
                .extracting(PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(tuple(PayoutFailureReason.AMOUNT_MISMATCH,
                        "초과 청구: 결제 30000, 후보 합계 45000, 넘친 금액 15000, 넘게 만든 주문상품 102, "
                                + "이미 지급 30000(주문상품 101), 혼자 넘는 주문상품 없음, 판매자 1명, 지갑 금액 충돌 없음"));
    }

    @Test
    void 값별_근거는_실패_상세_글과_같은_값이다() {
        // given: 위와 같은 주문 (결제 30,000, 101 30,000 지급됨, 102 15,000) + 지갑 금액 충돌 20,000·25,000
        PayoutCandidateItem paid = saleAmount(ORDER_ID, 101L, 10L, 30_000L);
        PayoutCandidateItem current = saleAmount(ORDER_ID, 102L, 11L, 15_000L);
        List<OverchargeCandidateRow> rows = List.of(row(paid, true), row(current, false));

        // when
        PayoutOverchargeEvidence sut = PayoutAmountValidator.overchargeEvidence(30_000L, 45_000L, rows,
                List.of(20_000L, 25_000L));

        // then: 상세 글 "넘친 금액 15000, 넘게 만든 주문상품 102, 이미 지급 30000(주문상품 101), 혼자 넘는 주문상품 없음, 판매자 2명,
        //   지갑 금액 충돌 있음(받은 금액 20000, 25000)"과 같은 값
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getDeductedAmount()).isEqualTo(30_000L);
            softly.assertThat(sut.getCandidateTotal()).isEqualTo(45_000L);
            softly.assertThat(sut.getOverflowAmount()).isEqualTo(15_000L);
            softly.assertThat(sut.getFirstOverflowItem()).isEqualTo("102");
            softly.assertThat(sut.getSettledAmount()).isEqualTo(30_000L);
            softly.assertThat(sut.settledItemList()).containsExactly("101");
            softly.assertThat(sut.aloneOverItemList()).isEmpty();
            softly.assertThat(sut.getSellerCount()).isEqualTo(2);
            softly.assertThat(sut.conflictAmountList()).containsExactly(20_000L, 25_000L);
        });
    }

    @Test
    void 누적합을_넘게_만든_후보가_배송비면_배송비로_남긴다() {
        // given: 대금 9,700 + 수수료 300 = 10,000까지는 결제 12,000 안, 뒤에 들어온 배송비 3,000이 넘김 (합계 13,000)
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        targets.add(shippingFee(ORDER_ID, 10L, 3_000L));
        givenOrder(ORDER_ID, 13_000L, 12_000L);
        givenOverchargeRowsFromTargets();

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut.get(0).getDetail()).contains("넘게 만든 주문상품 배송비");
    }

    @Test
    void 한_주문상품의_합계가_혼자_결제_금액보다_크면_혼자_넘는_주문상품으로_남긴다() {
        // given: 결제 20,000에 주문상품 101(5,000)과 102(대금 24,250 + 수수료 750 = 25,000)
        targets.add(saleAmount(ORDER_ID, 101L, 10L, 5_000L));
        givenNormalOrderItem(ORDER_ID, 102L, 10L, 24_250L, 750L);
        givenOrder(ORDER_ID, 30_000L, 20_000L);
        givenOverchargeRowsFromTargets();

        // when
        List<PayoutFailure> sut = validate();

        // then: 101(5,000)은 혼자 넘지 않아 102만 남음 (대금·수수료를 주문상품별로 합쳐 비교)
        assertThat(sut.get(0).getDetail()).contains("혼자 넘는 주문상품 102,");
    }

    @Test
    void 지갑_금액_충돌이_있으면_받은_금액을_남긴다() {
        // given: 15,000으로 저장된 차감 기록에 20,000 이벤트가 온 적이 있음
        targets.add(saleAmount(ORDER_ID, 101L, 10L, 16_000L));
        givenOrder(ORDER_ID, 16_000L, 15_000L);
        givenOverchargeRowsFromTargets();
        conflicts.put(ORDER_ID, List.of(20_000L));

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut.get(0).getDetail()).endsWith("지갑 금액 충돌 있음(받은 금액 20000)");
    }

    @Test
    void 근거_조회에_후보가_없어_넘게_만든_후보를_찾지_못하면_판별_불가로_남긴다() {
        // given: 합계로는 초과지만 근거 조회 결과가 비어 있음 (두 조회가 서로 다른 값을 본 이상한 경우)
        targets.add(saleAmount(ORDER_ID, 101L, 10L, 16_000L));
        givenOrder(ORDER_ID, 16_000L, 15_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 실패 기록은 그대로 남기고(근거가 없다고 건너뛰지 않음) 상세에 판별 불가
        assertThat(sut)
                .extracting(PayoutFailure::getReason)
                .containsExactly(PayoutFailureReason.AMOUNT_MISMATCH);
        assertThat(sut.get(0).getDetail()).contains("넘게 만든 주문상품 판별 불가");
    }

    @Test
    void 초과_청구_상세가_255자를_넘으면_잘라서_기록한다() {
        // given: 이미 지급된 주문상품 60개 + 이번 후보 1개 → 이미 지급 목록이 길어짐
        for (long orderItemId = 1_000_001L; orderItemId <= 1_000_060L; orderItemId++) {
            overchargeRows.add(row(saleAmount(ORDER_ID, orderItemId, 10L, 100L), true));
        }
        PayoutCandidateItem current = saleAmount(ORDER_ID, 2_000_001L, 10L, 1_000L);
        targets.add(current);
        overchargeRows.add(row(current, false));
        givenOrder(ORDER_ID, 7_000L, 6_500L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 예외 없이 DB 길이에 맞게 잘리고 끝에 "..."
        assertThat(sut).hasSize(1);
        assertThat(sut.get(0).getDetail()).hasSize(PayoutFailure.MAX_DETAIL_LENGTH).endsWith("...");
    }

    @Test
    void 초과_청구_주문_고르기는_검사와_같은_공식이고_차감_기록이_있는_주문만_본다() {
        // given: 100 = 초과, 200 = 같음(통과), 300 = 차감 기록 없음
        totals.put(100L, 10_001L);
        deductions.put(100L, 10_000L);
        totals.put(200L, 10_000L);
        deductions.put(200L, 10_000L);
        totals.put(300L, 99_999L);

        // when
        Set<Long> sut = PayoutAmountValidator.overchargedOrderIds(totals, deductions);

        // then
        assertThat(sut).containsExactly(100L);
    }

    // ---------- 수수료 ----------

    @Test
    void 수수료가_계산과_다르면_원인_주문상품은_수수료_불일치_같은_주문의_나머지는_보류다() {
        // given: 주문상품 1001(판매자 10)은 수수료 500(계산 309), 주문상품 1002(판매자 20)는 정상, 배송비(판매자 10)
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_800L, 500L);
        givenNormalOrderItem(ORDER_ID, 1002L, 20L, 19_400L, 600L);
        targets.add(shippingFee(ORDER_ID, 10L, 3_000L));
        givenOrder(ORDER_ID, 33_300L, 40_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        String feeDetail = "수수료 불일치: 기대값 309, 실제값 500 (판매 금액 10300 기준)";
        String lockedDetail = "같은 주문의 수수료 불일치로 보류 (원인 주문상품 [1001])";
        assertThat(sut)
                .extracting(PayoutFailure::getOrderItemId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(1001L, PayoutFailureReason.FEE_MISMATCH, feeDetail),   // 대금
                        tuple(1001L, PayoutFailureReason.FEE_MISMATCH, feeDetail),   // 수수료
                        tuple(1002L, PayoutFailureReason.ORDER_HELD, lockedDetail),
                        tuple(1002L, PayoutFailureReason.ORDER_HELD, lockedDetail),
                        tuple(null, PayoutFailureReason.ORDER_HELD, lockedDetail));   // 배송비
    }

    @Test
    void 한_주문에_수수료가_틀린_주문상품이_2개여도_후보마다_실패가_1건이다() {
        // given: 판매자 10의 1001, 판매자 20의 1002 둘 다 수수료가 틀림
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_800L, 500L);
        givenNormalOrderItem(ORDER_ID, 1002L, 20L, 19_000L, 1_000L);
        givenOrder(ORDER_ID, 30_300L, 40_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 후보 4건에 4건, 같은 후보 ID가 두 번 나오지 않음 (실패 기록 UK 위반 방지)
        assertThat(sut).extracting(PayoutFailure::getCandidateId).containsExactly(1L, 2L, 3L, 4L);
        assertThat(sut).extracting(PayoutFailure::getReason).containsOnly(PayoutFailureReason.FEE_MISMATCH);
    }

    @Test
    void 원래_대금_수수료_짝이_없으면_수수료_검사를_건너뛴다() {
        // given: 수수료 후보만 있음 (계산할 판매 금액을 알 수 없음)
        PayoutCandidateItem fee = saleFee(ORDER_ID, 1001L, 10L, 999L);
        targets.add(fee);
        originals.add(fee);
        givenOrder(ORDER_ID, 999L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 대금이_이미_정산되어_이번_스냅샷에_수수료만_있어도_짝을_맞춰_판정한다() {
        // given: 대금은 지난 배치에 정산됨(검사 대상 아님), 이번엔 수수료만. 원래 후보 조회에는 둘 다 있음
        PayoutCandidateItem amount = saleAmount(ORDER_ID, 1001L, 10L, 9_700L);
        PayoutCandidateItem fee = saleFee(ORDER_ID, 1001L, 10L, 300L);
        targets.add(fee);
        originals.add(amount);
        originals.add(fee);
        givenOrder(ORDER_ID, 10_000L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 조정_후보는_수수료_계산에_들어가지_않아_검사에_걸리지_않는다() {
        // given: 원래 대금 9,700·수수료 300(정상) + 수수료 조정 -100. 원래 후보 조회에는 조정이 없음
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        targets.add(adjustment(ORDER_ID, 1001L, 10L, -100L));
        givenOrder(ORDER_ID, 9_900L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 원래_후보_없이_조정만_있는_주문은_예외_없이_금액_불일치로_뺀다() {
        // given: 주문에 조정 후보만 있고 차감 기록은 있음. 합계는 조정을 빼서(#552) 이 주문의 키가 없음
        PayoutCandidateItem adjustmentOnly = adjustment(ORDER_ID, 1001L, 10L, 5_000L);
        targets.add(adjustmentOnly);
        deductions.put(ORDER_ID, 10_000L);

        // when: 합계를 그대로 꺼내면 NPE로 validate가 롤백되어 Job 전체가 실패했음
        List<PayoutFailure> sut = validate();

        // then: 검사할 기준이 없어 내보내지 않고 금액 불일치로 뺌
        assertThat(sut)
                .extracting(PayoutFailure::getCandidateId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(tuple(adjustmentOnly.getId(), PayoutFailureReason.AMOUNT_MISMATCH,
                        "초과 청구 판정 불가: 원래 후보 없이 조정 후보만 있음"));
    }

    @ParameterizedTest
    @MethodSource("수수료가_0원인_판매")
    void 수수료가_0원인_판매도_통과한다(long saleAmount, long fee) {
        // given
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, saleAmount, fee);
        givenOrder(ORDER_ID, saleAmount + fee, saleAmount + fee);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).isEmpty();
    }

    // 0원 상품, 버림으로 수수료가 0원인 상품 (30원 × 3% = 0.9 → 0)
    static Stream<Arguments> 수수료가_0원인_판매() {
        return Stream.of(
                Arguments.of(0L, 0L),
                Arguments.of(30L, 0L));
    }

    @Test
    void 금액이_틀린_주문만_실패이고_다른_주문은_통과한다() {
        // given: ORDER_ID는 초과 청구, OTHER_ORDER_ID는 정상
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 300L);
        givenOrder(ORDER_ID, 10_000L, 9_999L);
        givenNormalOrderItem(OTHER_ORDER_ID, 2001L, 10L, 9_700L, 300L);
        givenOrder(OTHER_ORDER_ID, 10_000L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then
        assertThat(sut).extracting(PayoutFailure::getOrderId).containsOnly(ORDER_ID);
    }

    @Test
    void 보류_상세가_255자를_넘으면_잘라서_기록한다() {
        // given: 수수료가 틀린 주문상품 30개 → 원인 목록이 길어짐, 배송비 1건은 보류
        for (long orderItemId = 1_000_001L; orderItemId <= 1_000_030L; orderItemId++) {
            givenNormalOrderItem(ORDER_ID, orderItemId, 10L, 9_800L, 500L);
        }
        targets.add(shippingFee(ORDER_ID, 10L, 3_000L));
        givenOrder(ORDER_ID, 312_000L, 400_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 배송비 후보의 보류 상세는 DB 길이에 맞게 잘리고 끝에 "..."
        PayoutFailure locked = sut.get(sut.size() - 1);
        assertThat(locked).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(locked.getReason()).isEqualTo(PayoutFailureReason.ORDER_HELD);
            softly.assertThat(locked.getDetail()).hasSize(PayoutFailure.MAX_DETAIL_LENGTH).endsWith("...");
        });
    }

    // ---------- 도우미 ----------

    private List<PayoutFailure> validate() {
        return PayoutAmountValidator.validate(SNAPSHOT_ID,
                new PayoutAmountValidator.Input(targets, totals, deductions, originals, overchargeRows, conflicts));
    }

    // 주문상품 하나의 원래 대금·수수료 후보를 검사 대상과 원래 후보 조회 결과에 함께 넣음 (둘 다 이번 스냅샷에 들어온 경우)
    private void givenNormalOrderItem(long orderId, long orderItemId, long sellerId, long amount, long fee) {
        PayoutCandidateItem amountCandidate = saleAmount(orderId, orderItemId, sellerId, amount);
        PayoutCandidateItem feeCandidate = saleFee(orderId, orderItemId, sellerId, fee);
        targets.add(amountCandidate);
        targets.add(feeCandidate);
        originals.add(amountCandidate);
        originals.add(feeCandidate);
    }

    // 주문의 후보 전체 합계와 지갑 차감액
    private void givenOrder(long orderId, long total, long deducted) {
        totals.put(orderId, total);
        deductions.put(orderId, deducted);
    }

    // 검사 대상 후보를 그대로 초과 청구 근거 행으로 넣음 (그 주문 후보가 모두 이번 스냅샷에 있고 아직 지급 전인 경우)
    // 넣은 순서 = 생성 순서
    private void givenOverchargeRowsFromTargets() {
        for (PayoutCandidateItem target : targets) {
            overchargeRows.add(row(target, false));
        }
    }

    // 후보 하나를 근거 행으로 (생성 시각은 넣는 순서대로 1분씩 늘림)
    private OverchargeCandidateRow row(PayoutCandidateItem candidate, boolean settled) {
        LocalDateTime createdAt = nextCreatedAt;
        nextCreatedAt = nextCreatedAt.plusMinutes(1);
        return new OverchargeCandidateRow(candidate.getId(), candidate.getOrderId(), candidate.getOrderItemId(),
                candidate.getSellerId(), candidate.getAmount(), createdAt, settled);
    }

    private PayoutCandidateItem saleAmount(long orderId, long orderItemId, long sellerId, long amount) {
        return withId(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, sellerId, amount));
    }

    private PayoutCandidateItem saleFee(long orderId, long orderItemId, long sellerId, long amount) {
        return withId(PayoutCandidateItem.ofSaleFee(orderId, orderItemId, sellerId, amount));
    }

    private PayoutCandidateItem shippingFee(long orderId, long sellerId, long amount) {
        return withId(PayoutCandidateItem.ofSaleShippingFee(orderId, sellerId, amount));
    }

    private PayoutCandidateItem adjustment(long orderId, long orderItemId, long sellerId, long amount) {
        return withId(PayoutCandidateItem.manualAdjustment(PayoutEventType.SALE_FEE,
                orderId, orderItemId, sellerId, amount, 1L, 99L, "테스트 조정"));
    }

    // 저장된 후보처럼 id를 채움 (실패 기록은 id가 없는 후보를 거부함). 넣은 순서대로 1, 2, 3...
    private PayoutCandidateItem withId(PayoutCandidateItem candidate) {
        ReflectionTestUtils.setField(candidate, "id", nextCandidateId++);
        return candidate;
    }
}
