package com.ok.payout.repository;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 정산후보 테이블의 저장·조회와 DB 제약(UK, CHECK)을 실제 MySQL + Flyway 마이그레이션으로 확인
// 자동 롤백이 없으므로 테스트마다 겹치지 않는 주문·주문항목 ID를 쓰고, 끝나면 이 테스트가 쓴 주문의 행만 지운다
class PayoutCandidateItemPersistenceIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 번호대 (다른 정산 테스트의 6_000_000~, 8_000_000~, 9_000_000~와 겹치지 않게)
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(5_500_000L);
    private static final AtomicLong ORDER_ITEM_ID_SEQ = new AtomicLong(5_000_000L);

    private static final Long SELLER_ID = 10L;
    private static final Long OTHER_SELLER_ID = 20L;

    // DB 제약 이름 (마이그레이션과 같아야 함). 어느 제약에 걸렸는지 확인하는 데 씀
    private static final String UK_ORDER_ITEM = "uk_payout_candidate_items_order_item_id_event_type_adjustment_id";
    private static final String UK_SHIPPING = "uk_payout_candidate_items_order_id_ship_seller_id_adjustment_id";
    private static final String CK_ORDER_ITEM = "ck_payout_candidate_items_event_type_order_item_id";

    @Autowired
    private PayoutCandidateItemRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 이 테스트에서 쓴 주문 ID (정리할 때 이 주문의 행만 지움)
    private final List<Long> usedOrderIds = new ArrayList<>();

    // 리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행
    // 주문 ID로 지우는 이유: 배송비 행은 order_item_id가 NULL이라 주문항목 ID로는 지울 수 없음
    @AfterEach
    void tearDown() {
        for (Long orderId : usedOrderIds) {
            jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id = ?", orderId);
        }
        usedOrderIds.clear();
    }

    // ---------- 기본 저장·조회 ----------

    @Test
    void 저장하면_id와_생성_수정_시각이_채워진다() {
        // given
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();

        // when
        Long id = repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L)).getId();

        // then
        PayoutCandidateItem sut = repository.findById(id).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getEventType()).isEqualTo(PayoutEventType.SALE_AMOUNT);
            softly.assertThat(sut.getOrderId()).isEqualTo(orderId);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(orderItemId);
            softly.assertThat(sut.getSellerId()).isEqualTo(SELLER_ID);
            softly.assertThat(sut.getAmount()).isEqualTo(9_700L);
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(sut.getUpdatedAt()).isNotNull();
        });
    }

    @Test
    void 저장된_정산후보는_값을_바꿔도_DB에_반영되지_않는다() {
        // given
        Long id = repository.save(
                PayoutCandidateItem.ofSaleAmount(nextOrderId(), nextOrderItemId(), SELLER_ID, 9_700L)).getId();

        // when: 변경 메서드가 없으니 트랜잭션 안에서 리플렉션으로 강제 변경 후 커밋
        transactionTemplate.executeWithoutResult(status -> {
            PayoutCandidateItem found = repository.findById(id).orElseThrow();
            ReflectionTestUtils.setField(found, "amount", 1L);
        });

        // then: @Immutable이라 UPDATE가 나가지 않아 원래 값 유지
        assertThat(repository.findById(id).orElseThrow().getAmount()).isEqualTo(9_700L);
    }

    @Test
    void 주문항목과_이벤트_타입으로_존재_여부를_조회한다() {
        // given
        Long orderItemId = nextOrderItemId();
        Long otherOrderItemId = nextOrderItemId();   // 저장하지 않은 주문항목
        repository.save(PayoutCandidateItem.ofSaleAmount(nextOrderId(), orderItemId, SELLER_ID, 9_700L));

        // when & then
        assertThat(repository.existsByOrderItemIdAndEventType(orderItemId, PayoutEventType.SALE_AMOUNT)).isTrue();
        assertThat(repository.existsByOrderItemIdAndEventType(orderItemId, PayoutEventType.SALE_FEE)).isFalse();
        assertThat(repository.existsByOrderItemIdAndEventType(otherOrderItemId, PayoutEventType.SALE_AMOUNT)).isFalse();
    }

    @Test
    void 주문_ID로_배송비를_포함한_정산후보를_조회한다() {
        // given
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L));
        repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, SELLER_ID, 3_000L));

        // when
        List<PayoutCandidateItem> sut = repository.findAllByOrderId(orderId);

        // then: 배송비 행은 주문항목 ID가 NULL이라 findAllByOrderItemId로는 안 나오고, 주문 ID로는 나옴
        assertThat(sut)
                .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getOrderItemId)
                .containsExactlyInAnyOrder(
                        tuple(PayoutEventType.SALE_AMOUNT, orderItemId),
                        tuple(PayoutEventType.SALE_SHIPPING_FEE, null)
                );
    }

    // ---------- 대금·수수료 중복 방지 UK(order_item_id, event_type, adjustment_id) ----------

    @Test
    void 관리자_조정이_아닌_평소_이벤트는_같은_주문항목_같은_이벤트_타입으로_두_번_저장할_수_없다() {
        // given
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L));

        // when & then: 평소 이벤트는 adjustment_id = 0으로 같아서 UK에 걸림
        assertThatThrownBy(() -> repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L)))
                .rootCause()
                .hasMessageContaining(UK_ORDER_ITEM);
    }

    @Test
    void 같은_주문항목이라도_이벤트_타입이_다르면_저장된다() {
        // given
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();

        // when
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L));
        repository.save(PayoutCandidateItem.ofSaleFee(orderId, orderItemId, SELLER_ID, 300L));

        // then
        assertThat(repository.findAllByOrderItemId(orderItemId)).hasSize(2);
    }

    @Test
    void 같은_주문항목_같은_이벤트_타입이라도_조정_번호가_다른_수동_조정은_모두_저장된다() {
        // given: 원래 평소 이벤트 후보 (adjustment_id = 0)
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, orderItemId, SELLER_ID, 9_700L));

        // when: 같은 주문항목·같은 타입으로 조정 번호만 다른 수동 조정 2건 (음수 차감 포함)
        repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, orderId, orderItemId, SELLER_ID, -500L, 1L, 99L, "금액 오입력 차감"));
        repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, orderId, orderItemId, SELLER_ID, 200L, 2L, 99L, "차감 과다 보정"));

        // then: 원래 후보 1건 + 조정 2건 = 3건, 조정 번호로 구분됨
        assertThat(repository.findAllByOrderItemId(orderItemId))
                .extracting(PayoutCandidateItem::getAdjustmentId)
                .containsExactlyInAnyOrder(0L, 1L, 2L);
    }

    @Test
    void 같은_조정_번호로_수동_조정을_두_번_저장할_수_없다() {
        // given: 조정 번호 1번으로 수동 조정 1건 저장
        Long orderId = nextOrderId();
        Long orderItemId = nextOrderItemId();
        repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, orderId, orderItemId, SELLER_ID, -500L, 1L, 99L, "금액 오입력 차감"));

        // when & then: 같은 조정 요청이 한 번 더 들어온 상황 (조정 번호가 같으면 UK 충돌)
        assertThatThrownBy(() -> repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, orderId, orderItemId, SELLER_ID, -500L, 1L, 99L, "금액 오입력 차감")))
                .rootCause()
                .hasMessageContaining(UK_ORDER_ITEM);
    }

    // ---------- 배송비 중복 방지 UK(order_id, ship_seller_id, adjustment_id) ----------

    @Test
    void 같은_주문_같은_판매자의_배송비는_두_번_저장할_수_없다() {
        // given
        Long orderId = nextOrderId();
        repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, SELLER_ID, 3_000L));

        // when & then: 같은 묶음의 다른 상품이 동시에 구매 확정되어 배송비가 한 번 더 들어온 상황
        assertThatThrownBy(() -> repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, SELLER_ID, 3_000L)))
                .rootCause()
                .hasMessageContaining(UK_SHIPPING);
    }

    @Test
    void 같은_주문이라도_판매자가_다르면_배송비가_각각_저장된다() {
        // given
        Long orderId = nextOrderId();

        // when
        repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, SELLER_ID, 3_000L));
        repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, OTHER_SELLER_ID, 2_500L));

        // then
        assertThat(repository.findAllByOrderId(orderId))
                .extracting(PayoutCandidateItem::getSellerId, PayoutCandidateItem::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(SELLER_ID, 3_000L),
                        tuple(OTHER_SELLER_ID, 2_500L)
                );
    }

    @Test
    void 같은_주문_같은_판매자라도_대금과_수수료는_주문상품마다_저장된다() {
        // given: 같은 묶음(주문, 판매자)의 상품 2개
        Long orderId = nextOrderId();
        Long firstOrderItemId = nextOrderItemId();
        Long secondOrderItemId = nextOrderItemId();

        // when
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, firstOrderItemId, SELLER_ID, 9_700L));
        repository.save(PayoutCandidateItem.ofSaleFee(orderId, firstOrderItemId, SELLER_ID, 300L));
        repository.save(PayoutCandidateItem.ofSaleAmount(orderId, secondOrderItemId, SELLER_ID, 4_850L));
        repository.save(PayoutCandidateItem.ofSaleFee(orderId, secondOrderItemId, SELLER_ID, 150L));

        // then: 배송비 UK는 배송비 행에만 걸리므로 대금·수수료 4건이 모두 저장됨
        assertThat(repository.findAllByOrderId(orderId)).hasSize(4);
    }

    @Test
    void 주문_판매자로_배송비_존재_여부를_조회한다() {
        // given
        Long orderId = nextOrderId();
        repository.save(PayoutCandidateItem.ofSaleShippingFee(orderId, SELLER_ID, 3_000L));
        // 다른 판매자는 조정 후보만 있음 (평소 이벤트 배송비 확인에서는 빠져야 함)
        repository.save(PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_SHIPPING_FEE, orderId, null, OTHER_SELLER_ID, -500L, 1L, 99L, "배송비 차감"));

        // when & then
        assertThat(repository.existsByOrderIdAndSellerIdAndEventTypeAndAdjustmentId(
                orderId, SELLER_ID, PayoutEventType.SALE_SHIPPING_FEE, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)).isTrue();
        assertThat(repository.existsByOrderIdAndSellerIdAndEventTypeAndAdjustmentId(
                orderId, OTHER_SELLER_ID, PayoutEventType.SALE_SHIPPING_FEE, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID)).isFalse();
    }

    // ---------- CHECK: 배송비만 order_item_id가 NULL ----------

    @Test
    void 배송비에_주문상품_ID가_있으면_저장할_수_없다() {
        // given: 팩토리(ofSaleShippingFee)로는 만들 수 없어서 수동 조정으로 재현
        PayoutCandidateItem shippingWithOrderItem = PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_SHIPPING_FEE, nextOrderId(), nextOrderItemId(), SELLER_ID, 3_000L, 1L, 99L, "잘못된 배송비");

        // when & then
        assertThatThrownBy(() -> repository.save(shippingWithOrderItem))
                .rootCause()
                .hasMessageContaining(CK_ORDER_ITEM);
    }

    @Test
    void 대금에_주문상품_ID가_없으면_저장할_수_없다() {
        // given
        PayoutCandidateItem amountWithoutOrderItem = PayoutCandidateItem.manualAdjustment(
                PayoutEventType.SALE_AMOUNT, nextOrderId(), null, SELLER_ID, 9_700L, 1L, 99L, "잘못된 대금");

        // when & then
        assertThatThrownBy(() -> repository.save(amountWithoutOrderItem))
                .rootCause()
                .hasMessageContaining(CK_ORDER_ITEM);
    }

    // 겹치지 않는 주문 ID를 만들고, 정리 대상으로 기록
    private Long nextOrderId() {
        Long id = ORDER_ID_SEQ.incrementAndGet();
        usedOrderIds.add(id);
        return id;
    }

    // 겹치지 않는 주문항목 ID를 만듦 (정리는 주문 ID로 하므로 따로 기록하지 않음)
    private Long nextOrderItemId() {
        return ORDER_ITEM_ID_SEQ.incrementAndGet();
    }
}
