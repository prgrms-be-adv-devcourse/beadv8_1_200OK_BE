package com.ok.payout.service;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // when
        List<PayoutFailure> sut = validate();

        // then: 어느 주문상품이 틀렸는지 모르므로 다른 판매자 후보까지 전부 같은 사유·상세
        assertThat(sut)
                .extracting(PayoutFailure::getSellerId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, overchargeDetail(13_000L, 13_001L)),
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, overchargeDetail(13_000L, 13_001L)),
                        tuple(10L, PayoutFailureReason.AMOUNT_MISMATCH, overchargeDetail(13_000L, 13_001L)),
                        tuple(20L, PayoutFailureReason.AMOUNT_MISMATCH, overchargeDetail(13_000L, 13_001L)));
    }

    @Test
    void 초과_청구에_걸린_주문은_수수료_검사를_하지_않아_후보마다_실패가_1건이다() {
        // given: 수수료도 틀리고(300이어야 하는데 500) 합계도 차감액을 넘음
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 9_700L, 500L);
        givenOrder(ORDER_ID, 10_200L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 초과 청구 상세만 있고 후보 2건에 1건씩
        assertThat(sut)
                .extracting(PayoutFailure::getCandidateId, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(1L, overchargeDetail(10_000L, 10_200L)),
                        tuple(2L, overchargeDetail(10_000L, 10_200L)));
    }

    // ---------- 수수료 ----------

    @Test
    void 수수료가_계산과_다르면_원인_주문상품은_금액_불일치_같은_주문의_나머지는_보류다() {
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
                        tuple(1001L, PayoutFailureReason.AMOUNT_MISMATCH, feeDetail),   // 대금
                        tuple(1001L, PayoutFailureReason.AMOUNT_MISMATCH, feeDetail),   // 수수료
                        tuple(1002L, PayoutFailureReason.ORDER_LOCKED, lockedDetail),
                        tuple(1002L, PayoutFailureReason.ORDER_LOCKED, lockedDetail),
                        tuple(null, PayoutFailureReason.ORDER_LOCKED, lockedDetail));   // 배송비
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
        assertThat(sut).extracting(PayoutFailure::getReason).containsOnly(PayoutFailureReason.AMOUNT_MISMATCH);
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
    void 수수료를_계산할_수_없을_만큼_큰_금액이면_예외_대신_그_주문상품을_금액_불일치로_본다() {
        // given: 판매 대금 4경 원 (수수료 계산 시 long 범위 초과). 같은 배치의 다른 주문은 정상
        givenNormalOrderItem(ORDER_ID, 1001L, 10L, 40_000_000_000_000_000L, 1L);
        givenOrder(ORDER_ID, 40_000_000_000_000_001L, 50_000_000_000_000_000L);
        givenNormalOrderItem(OTHER_ORDER_ID, 2001L, 10L, 9_700L, 300L);
        givenOrder(OTHER_ORDER_ID, 10_000L, 10_000L);

        // when
        List<PayoutFailure> sut = validate();

        // then: 큰 금액 주문의 후보 2건만 실패 (validate가 예외로 멈춰 정상 주문까지 막히지 않음)
        String detail = "수수료 계산 불가: 금액 범위 초과 (판매 대금 40000000000000000, 수수료 1)";
        assertThat(sut)
                .extracting(PayoutFailure::getOrderId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(ORDER_ID, PayoutFailureReason.AMOUNT_MISMATCH, detail),
                        tuple(ORDER_ID, PayoutFailureReason.AMOUNT_MISMATCH, detail));
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
            softly.assertThat(locked.getReason()).isEqualTo(PayoutFailureReason.ORDER_LOCKED);
            softly.assertThat(locked.getDetail()).hasSize(PayoutFailure.MAX_DETAIL_LENGTH).endsWith("...");
        });
    }

    // ---------- 도우미 ----------

    private List<PayoutFailure> validate() {
        return PayoutAmountValidator.validate(SNAPSHOT_ID,
                new PayoutAmountValidator.Input(targets, totals, deductions, originals));
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

    private String overchargeDetail(long deducted, long total) {
        return "주문 합계 초과: 차감액 " + deducted + ", 후보 합계 " + total + " (어느 주문상품이 틀렸는지 판별 불가)";
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
