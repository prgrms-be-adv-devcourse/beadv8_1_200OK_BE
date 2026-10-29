package com.ok.refund.repository;

import com.ok.refund.domain.CancelledQuantity;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static com.ok.refund.fixture.RefundFixture.NOW;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 마이그레이션과 엔티티가 맞는지(ddl validate), 진행 중 칸 UNIQUE와 완료 수량 합 쿼리가 실제 DB에서 동작하는지 확인한다
class RefundRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RefundRepository refundRepository;

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
    }

    @Test
    void 같은_주문_상품에_진행_중인_요청이_있으면_두_번째_저장은_유니크_제약에_걸린다() {
        // given
        refundRepository.saveAndFlush(requested(1));

        // when & then
        assertThatThrownBy(() -> refundRepository.saveAndFlush(requested(1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 앞_요청이_끝_상태면_같은_주문_상품에_새_요청을_저장할_수_있다() {
        // given
        Refund first = requested(1);
        first.withdraw(NOW);
        refundRepository.saveAndFlush(first);

        // when
        refundRepository.saveAndFlush(requested(1));

        // then
        assertThat(refundRepository.count()).isEqualTo(2);
    }

    @Test
    void 완료된_요청의_개수만_더한다() {
        // given
        Refund completed = requested(2);
        completed.startProcessing(0L, 0L, NOW);
        completed.complete(NOW);
        refundRepository.saveAndFlush(completed);
        refundRepository.saveAndFlush(requested(1));

        // when
        long result = refundRepository.sumQuantityByOrderItemIdAndStatus(ORDER_ITEM_ID, RefundStatus.COMPLETED);

        // then
        assertThat(result).isEqualTo(2L);
    }

    @Test
    void 같은_묶음에서_처리_중과_완료_개수를_주문_상품별로_더한다() {
        // given: 양말 완료 2 + 처리 중 1, 모자 요청됨 1(세지 않음)
        Refund completed = requested(2);
        completed.startProcessing(0L, 0L, NOW);
        completed.complete(NOW);
        refundRepository.saveAndFlush(completed);
        Refund processing = requested(1);
        processing.startProcessing(0L, 0L, NOW);
        refundRepository.saveAndFlush(processing);
        refundRepository.saveAndFlush(requested(102L, 1));

        // when
        List<CancelledQuantity> result = refundRepository.sumQuantityByOrderItem(ORDER_ID, SELLER_ID,
                List.of(RefundStatus.PROCESSING, RefundStatus.COMPLETED));

        // then
        assertThat(result).containsExactly(new CancelledQuantity(ORDER_ITEM_ID, 3L));
    }

    @Test
    void 완료된_요청이_없으면_0을_돌려준다() {
        // when
        long result = refundRepository.sumQuantityByOrderItemIdAndStatus(ORDER_ITEM_ID, RefundStatus.COMPLETED);

        // then
        assertThat(result).isZero();
    }
}
