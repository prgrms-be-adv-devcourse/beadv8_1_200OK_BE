package com.ok.refund.adapter.out.persistence;

import com.ok.common.exception.RestApiException;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 명시적 저장(#481)의 핵심 규칙을 실제 DB로 확인한다. 어댑터는 트랜잭션을 열지 않으므로 서비스처럼 TransactionTemplate 안에서 부른다.
// 저장소를 따로 테스트하지 않는 규칙(testing-strategy.md)의 예외다. "잠그지 않은 환불은 update하지 못한다", "도메인을 바꿔도 update 전에는
// 엔티티가 그대로다"는 서비스 경로로는 일부러 만들 수 없어 결과에 드러나지 않고, 이 둘이 이번 분리의 이유라 어댑터를 직접 본다
class RefundPersistenceAdapterIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 10, 0);
    private static final Long ORDER_ID = 70L;
    private static final Long ORDER_ITEM_ID = 701L;
    private static final Long CABBAGE_ORDER_ITEM_ID = 702L;
    private static final Long SELLER_ID = 7L;

    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    RefundStepJpaRepository refundStepJpaRepository;
    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        // 단계 줄이 환불 행을 외래 키로 가리켜 먼저 지운다(승인 흐름이 단계 줄을 만든다, #574)
        refundStepJpaRepository.deleteAllInBatch();
        refundJpaRepository.deleteAllInBatch();
    }

    @Test
    void 새로_저장하면_ID와_생성_시각이_채워진_도메인을_돌려준다() {
        // given
        Refund refund = requested(ORDER_ITEM_ID);

        // when
        Refund sut = inTransaction(() -> refundPersistencePort.saveNew(refund));

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getId()).isNotNull();
            softly.assertThat(sut.getCreatedAt()).isNotNull();
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REQUESTED);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
            softly.assertThat(refund.getId()).isNull();
        });
    }

    @Test
    void 잠금_조회한_환불을_update하면_바뀌는_칸이_모두_저장된다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when: 서비스처럼 한 트랜잭션에서 잠그고, 바꾸고, update한다
        inTransaction(() -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            locked.approve(NOW, new RefundShippingFee(3_000L, 2_000L, 1_000L, true));
            refundPersistencePort.update(locked);
            return null;
        });

        // then
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getDecidedAt()).isEqualTo(NOW);
            softly.assertThat(sut.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isEqualTo(2_000L);
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isEqualTo(1_000L);
            softly.assertThat(sut.getActiveOrderItemId()).isEqualTo(ORDER_ITEM_ID);
        });
    }

    // 끝 상태가 되면 진행 중 칸과 거절 사유도 update로 함께 저장돼야 같은 상품의 새 요청을 받는다
    @Test
    void 거절을_update하면_거절_사유가_남고_진행_중_칸이_비워진다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when
        inTransaction(() -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            locked.reject("이미 포장을 마쳤습니다.", NOW);
            refundPersistencePort.update(locked);
            return null;
        });

        // then
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(sut.getRejectReason()).isEqualTo("이미 포장을 마쳤습니다.");
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    // 앞선 update가 집계 조회의 자동 flush로 먼저 나가면 Hibernate가 잠금 모드를 PESSIMISTIC_WRITE에서 PESSIMISTIC_FORCE_INCREMENT로 바꾼다.
    // 쓰기 잠금은 그대로 이 트랜잭션에 있으므로 같은 트랜잭션의 다음 update도 받아야 한다
    @Test
    void 같은_트랜잭션에서_update가_flush된_뒤에_다시_update해도_마지막_값이_저장된다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when
        inTransaction(() -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            locked.approve(NOW, RefundShippingFee.none());
            refundPersistencePort.update(locked);
            refundPersistencePort.sumCancelledByOrderItem(ORDER_ID, SELLER_ID, List.of(RefundStatus.PROCESSING));
            locked.complete(NOW.plusMinutes(1));
            refundPersistencePort.update(locked);
            return null;
        });

        // then
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(NOW.plusMinutes(1));
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    // 잠그지 않고(같은 트랜잭션에서 불러오지 않고) update하면 다른 트랜잭션이 잠근 행을 잠금 없이 덮어쓸 수 있어 막아야 한다
    @Test
    void 이_트랜잭션에서_불러오지_않은_환불을_update하면_예외를_던지고_바꾸지_않는다() {
        // given: 앞 트랜잭션에서 읽어 둔 환불을 다음 트랜잭션에서 바꾼다
        Refund saved = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID)));
        saved.withdraw(NOW);

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.update(saved);
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이 트랜잭션에서 잠금 조회로 불러온 환불만 update할 수 있습니다. refundId=" + saved.getId());
        assertThat(refundPersistencePort.findById(saved.getId()).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 같은 트랜잭션에서 불러왔어도 잠그지 않은 조회(findById)라면, 다른 트랜잭션이 잠그고 바꾸는 중에도 기다리지 않고 덮어쓸 수 있다.
    // 그래서 "불러왔는가"만이 아니라 "잠금 조회로 불러왔는가"까지 막는지 본다
    // 잠금 여부는 Hibernate가 알려 주는 잠금 모드(getLockMode)로 판단한다. Hibernate를 올린 뒤 잠그지 않은 조회도 잠금으로 보고하게 바뀌면
    // 이 테스트만 깨진다. 그때는 테스트를 고치지 말고 어댑터의 잠금 확인 방식을 다시 정한다
    @Test
    void 같은_트랜잭션에서_잠그지_않고_불러온_환불을_update하면_예외를_던지고_바꾸지_않는다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            Refund unlocked = refundPersistencePort.findById(refundId).orElseThrow();
            unlocked.withdraw(NOW);
            refundPersistencePort.update(unlocked);
            return null;
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이 트랜잭션에서 잠금 조회로 불러온 환불만 update할 수 있습니다. refundId=" + refundId);
        assertThat(refundPersistencePort.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 분리 전에는 엔티티를 바꾸는 순간 그 뒤의 집계 쿼리가 자동 flush로 바뀐 값을 읽었다(승인 때 "개수 초과"의 원인).
    // 이제는 update 전까지 엔티티가 그대로라, 같은 트랜잭션의 집계에도 바꾼 상태가 보이지 않아야 한다
    @Test
    void 도메인을_바꿔도_update_전에는_같은_트랜잭션의_집계에_보이지_않는다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when
        List<CancelledQuantity> sut = inTransaction(() -> {
            Refund locked = refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            locked.approve(NOW, RefundShippingFee.none());
            return refundPersistencePort.sumCancelledByOrderItem(ORDER_ID, SELLER_ID, List.of(RefundStatus.PROCESSING));
        });

        // then
        assertThat(sut).isEmpty();
    }

    @Test
    void 같은_주문_상품에_진행_중인_요청이_있으면_진행_중_에러를_던진다() {
        // given
        inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID)));

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
        assertThat(refundJpaRepository.count()).isOne();
    }

    // 같은 트랜잭션에서 같은 환불을 두 번 잠금 조회하면, 두 객체 중 옛 객체로 나중에 update할 때 앞의 변경이 사라진다(#486 3번)
    @Test
    void 같은_트랜잭션에서_같은_환불을_두_번_잠금_조회하면_예외를_던진다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(refundId);
            return refundPersistencePort.findByIdForUpdate(refundId);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("같은 트랜잭션에서 같은 환불을 두 번 잠금 조회할 수 없습니다. 처음 잠근 객체로 바꾸고 저장해야 합니다. refundId=" + refundId);
    }

    // 막는 것은 "한 트랜잭션 안의 두 번째"뿐이다. 트랜잭션이 끝나면 기록을 지워 다음 트랜잭션은 다시 잠글 수 있어야 한다(승인 → 완료가 이 순서)
    @Test
    void 트랜잭션이_끝나면_같은_환불을_다시_잠금_조회할_수_있다() {
        // given
        Long refundId = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();
        inTransaction(() -> refundPersistencePort.findByIdForUpdate(refundId));

        // when
        Refund sut = inTransaction(() -> refundPersistencePort.findByIdForUpdate(refundId)).orElseThrow();

        // then
        assertThat(sut.getId()).isEqualTo(refundId);
    }

    // 새로 저장한 행을 같은 트랜잭션에서 잠그고 고치는 흐름은 첫 잠금 조회라 막지 않는다
    @Test
    void 새로_저장한_뒤_같은_트랜잭션에서_잠금_조회하고_update할_수_있다() {
        // when
        Long refundId = inTransaction(() -> {
            Refund saved = refundPersistencePort.saveNew(requested(ORDER_ITEM_ID));
            Refund locked = refundPersistencePort.findByIdForUpdate(saved.getId()).orElseThrow();
            locked.withdraw(NOW);
            refundPersistencePort.update(locked);
            return saved.getId();
        });

        // then
        assertThat(refundPersistencePort.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.WITHDRAWN);
    }

    // 안쪽 새 트랜잭션이 잠근 ID는 안쪽 목록에만 남아야 한다. 섞이면 바깥에서 처음 잠그는 환불을 두 번째로 보고 막는다
    @Test
    void 안에서_새_트랜잭션이_잠근_환불은_바깥에서_처음_잠글_수_있다() {
        // given
        Long potato = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();
        Long cabbage = inTransaction(() -> refundPersistencePort.saveNew(requested(CABBAGE_ORDER_ITEM_ID))).getId();
        TransactionTemplate inner = requiresNew();

        // when
        Refund sut = inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(potato);
            inner.execute(status -> refundPersistencePort.findByIdForUpdate(cabbage));
            return refundPersistencePort.findByIdForUpdate(cabbage);
        }).orElseThrow();

        // then
        assertThat(sut.getId()).isEqualTo(cabbage);
    }

    // 안쪽이 끝나 돌아오면 바깥 목록을 다시 붙여야 한다. 붙이지 않으면 바깥에서 같은 환불을 두 번 잠가도 모른다
    @Test
    void 안쪽_새_트랜잭션이_끝나도_바깥이_잠근_환불은_다시_잠글_수_없다() {
        // given
        Long potato = inTransaction(() -> refundPersistencePort.saveNew(requested(ORDER_ITEM_ID))).getId();
        Long cabbage = inTransaction(() -> refundPersistencePort.saveNew(requested(CABBAGE_ORDER_ITEM_ID))).getId();
        TransactionTemplate inner = requiresNew();

        // when & then
        assertThatThrownBy(() -> inTransaction(() -> {
            refundPersistencePort.findByIdForUpdate(potato);
            inner.execute(status -> refundPersistencePort.findByIdForUpdate(cabbage));
            return refundPersistencePort.findByIdForUpdate(potato);
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("같은 트랜잭션에서 같은 환불을 두 번 잠금 조회할 수 없습니다. 처음 잠근 객체로 바꾸고 저장해야 합니다. refundId=" + potato);
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private static Refund requested(Long orderItemId) {
        return Refund.request(ORDER_ID, orderItemId, 1, 1L, SELLER_ID, 77L, "상품", "옵션", 10_000L, null);
    }
}
