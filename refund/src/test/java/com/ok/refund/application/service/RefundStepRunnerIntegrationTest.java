package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.adapter.out.persistence.RefundStepJpaRepository;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.in.DecideRefundUseCase;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundRequestCommand;
import com.ok.refund.application.port.in.RequestRefundUseCase;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderQueryPort;
import com.ok.testsupport.FakeProductStockReleasePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 기록이 서비스의 트랜잭션 구간 밖에서 불려도 따로 커밋되어 DB에 남는지, 새 칸이 엔티티와 맞게 저장되는지 실제 DB로 확인한다
class RefundStepRunnerIntegrationTest extends AbstractIntegrationTest {

    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long ORDER_ID = 60L;
    private static final Long ORDER_ITEM_ID = 601L;

    @Autowired
    RefundStepRunner refundStepRunner;
    @Autowired
    RequestRefundUseCase requestRefundUseCase;
    @Autowired
    DecideRefundUseCase decideRefundUseCase;
    // 조회 확인은 출력 포트(도메인)로 한다. 테이블 비우기는 포트에 없는 테스트 전용 작업이라 JPA 리포지토리로 한다
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    RefundStepPersistencePort refundStepPersistencePort;
    @Autowired
    RefundStepJpaRepository refundStepJpaRepository;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    @Autowired
    FakeProductStockReleasePort productStockReleasePort;
    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void tearDown() {
        // 단계 줄이 환불 행을 외래키로 참조하므로 먼저 지운다
        refundStepJpaRepository.deleteAllInBatch();
        refundJpaRepository.deleteAllInBatch();
        orderQueryPort.clear();
        productStockReleasePort.clear();
    }

    @Test
    void 실패하면_DB의_단계_줄이_할_차례로_돌아가고_이유가_남는다() {
        // given
        Long refundId = processingRefundId();
        startOnly(refundId, RefundStepType.ORDER);

        // when
        assertThatThrownBy(() -> refundStepRunner.run(refundId, RefundStepType.ORDER, () -> {
            throw new RestApiException(RefundErrorCode.ORDER_API_FAILED);
        })).isInstanceOfSatisfying(RestApiException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_API_FAILED));

        // then
        RefundStep sut = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.ORDER).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(sut.getLastReason()).isEqualTo("ORDER_API_FAILED");
            softly.assertThat(sut.getNextCheckAt()).isNotNull();
        });
        // 환불은 처리 중 그대로다. 멈춘 곳은 단계 줄에만 남는다(결정 3)
        Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PROCESSING);
    }

    // 칸 길이가 엔티티보다 짧으면 운영에서 기록이 "Data too long"으로 실패하고, 그 실패는 원래 예외에 붙어 응답에 드러나지 않는다.
    // 그래서 모든 단계 이름과 엔티티가 허용하는 가장 긴 이유(100자)가 실제 칸에 들어가는지 본다
    @ParameterizedTest
    @MethodSource("모든_실패_단계")
    void 모든_단계와_가장_긴_이유가_DB에_저장된다(RefundStepType step) {
        // given
        Long refundId = processingRefundId();
        startOnly(refundId, step);
        String longestFailureCode = "E".repeat(100);

        // when
        refundStepRunner.recordFailure(refundId, step, longestFailureCode);

        // then
        RefundStep sut = refundStepPersistencePort.findByRefundIdAndType(refundId, step).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getType()).isEqualTo(step);
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStepStatus.READY);
            softly.assertThat(sut.getLastReason()).isEqualTo(longestFailureCode);
        });
    }

    static Stream<RefundStepType> 모든_실패_단계() {
        return Stream.of(RefundStepType.values());
    }

    // 바깥 트랜잭션 안에서 부르면 기록이 거기에 합류해 원래 예외로 롤백될 때 함께 사라진다. 다른 모듈을 부르기 전에 막아야 한다
    @Test
    void 트랜잭션_안에서_부르면_다른_모듈을_부르기_전에_막는다() {
        // given
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        AtomicBoolean called = new AtomicBoolean(false);

        // when & then
        assertThatThrownBy(() -> outer.executeWithoutResult(status ->
                refundStepRunner.run(1L, RefundStepType.ORDER, () -> {
                    called.set(true);
                    return null;
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불 단계 실행은 트랜잭션 밖에서 불러야 합니다. 실패 기록이 바깥 트랜잭션과 함께 롤백됩니다.");
        assertThat(called).isFalse();
    }

    // 승인 흐름이 남긴 단계 줄(주문 성공, 지갑 결과 대기)은 지우고 그 단계의 "시도 중" 줄 하나로 시작한다.
    // 실패 기록은 끝나지 않은 줄에만 남고, 서비스가 호출 전에 만들어 두는 줄이 이것이다
    private void startOnly(Long refundId, RefundStepType step) {
        refundStepJpaRepository.deleteAllInBatch();
        // 단계 줄은 환불 행을 잠근 트랜잭션에서만 만들 수 있다
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            refundStepPersistencePort.saveNew(RefundStep.start(refundId, step, LocalDateTime.now()));
        });
    }

    // 요청한 뒤 판매자가 승인해 처리 중이 된 환불. 실패 기록은 처리 중에만 남길 수 있다
    private Long processingRefundId() {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 1)));
        orderQueryPort.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 77L, "상품", "옵션",
                RefundableOrderItem.CANCELLABLE_STATUS, 10_000L, 1, bundle));
        Long refundId = requestRefundUseCase.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1, null)).refundId();
        // 돌려줄 돈이 있으면 승인은 지갑 요청까지만 하고 처리 중으로 응답한다(#544). 지갑 완료 메시지는 보내지 않아 처리 중으로 남는다.
        // 실패 기록은 처리 중인 환불에만 남길 수 있다
        decideRefundUseCase.decide(new RefundDecisionCommand(refundId, SELLER_ID, RefundDecision.APPROVE, null));
        return refundId;
    }
}
