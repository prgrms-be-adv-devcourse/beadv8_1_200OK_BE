package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundDecision;
import com.ok.refund.service.dto.RefundDecisionCommand;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeRefundDeliveryClient;
import com.ok.testsupport.FakeRefundOrderClient;
import com.ok.testsupport.FakeRefundProductClient;
import com.ok.testsupport.FakeRefundWalletClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.ok.refund.fixture.RefundFixture.BUYER_ID;
import static com.ok.refund.fixture.RefundFixture.HAT_ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ID;
import static com.ok.refund.fixture.RefundFixture.ORDER_ITEM_ID;
import static com.ok.refund.fixture.RefundFixture.SELLER_ID;
import static com.ok.refund.fixture.RefundFixture.hat;
import static com.ok.refund.fixture.RefundFixture.socks;
import static org.assertj.core.api.Assertions.assertThat;

// 같은 판매자 묶음 동시 승인, 철회·승인 경합, 같은 상품 동시 요청을 실제 DB 잠금으로 확인한다.
// 실행마다 어느 쪽이 먼저 잠금을 얻을지 달라지므로 여러 번 돌려 두 순서를 모두 거치게 한다
class RefundConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String SUCCESS = "SUCCESS";

    @Autowired
    RefundFacade refundFacade;
    @Autowired
    RefundService refundService;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    FakeRefundOrderClient orderClient;
    @Autowired
    FakeRefundDeliveryClient deliveryClient;
    @Autowired
    FakeRefundProductClient productClient;
    @Autowired
    FakeRefundWalletClient walletClient;

    @BeforeEach
    void setUp() {
        orderClient.put(socks());
        orderClient.put(hat());
        deliveryClient.addShipment(ORDER_ITEM_ID);
        deliveryClient.addShipment(HAT_ORDER_ITEM_ID);
    }

    @AfterEach
    void tearDown() {
        refundRepository.deleteAllInBatch();
        orderClient.clear();
        deliveryClient.clear();
        productClient.clear();
        walletClient.clear();
    }

    @RepeatedTest(5)
    void 같은_묶음을_동시에_승인하면_배송비는_한_요청만_받는다() throws Exception {
        // given: 양말 3개, 모자 1개를 각각 요청 → 둘 다 승인하면 묶음 전부 취소
        Long socksRefund = refundFacade.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 3)).refundId();
        Long hatRefund = refundFacade.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, HAT_ORDER_ITEM_ID, 1)).refundId();

        // when
        List<String> results = runTogether(
                () -> refundFacade.decide(approve(socksRefund)),
                () -> refundFacade.decide(approve(hatRefund)));

        // then: 30,000 + 5,000 + 배송비·추가 운임 6,000은 한 번만 = 결제 41,000원
        assertThat(results).containsOnly(SUCCESS);
        assertThat(walletClient.totalAmount()).isEqualTo(41_000L);
        // 배송비와 추가 운임을 함께 받은 요청이 정확히 한 건이고, 나머지는 둘 다 0이어야 한다
        List<Long> feesPerRefund = new ArrayList<>();
        for (Refund refund : refundRepository.findAll()) {
            feesPerRefund.add(refund.getShippingFeeAmount() + refund.getRemoteAreaFeeAmount());
        }
        assertThat(feesPerRefund).containsExactlyInAnyOrder(6_000L, 0L);
    }

    @RepeatedTest(5)
    void 철회와_승인이_동시에_오면_한쪽만_성공한다() throws Exception {
        // given
        Long refundId = refundFacade.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1)).refundId();

        // when
        List<String> results = runTogether(
                () -> refundService.withdraw(refundId, BUYER_ID),
                () -> refundFacade.decide(approve(refundId)));

        // then
        assertThat(results).containsOnlyOnce(SUCCESS);
        assertThat(results).contains(RefundErrorCode.INVALID_REFUND_STATE.name());
        RefundStatus status = refundRepository.findById(refundId).orElseThrow().getStatus();
        if (status == RefundStatus.WITHDRAWN) {
            // 철회가 이겼으면 배송 잠금도 돈도 나가지 않아야 한다
            assertThat(deliveryClient.reservedRefundIds()).isEmpty();
            assertThat(walletClient.refunded()).isEmpty();
        } else {
            assertThat(status).isEqualTo(RefundStatus.COMPLETED);
        }
    }

    @RepeatedTest(5)
    void 같은_주문_상품에_요청이_동시에_오면_한_건만_저장되고_나머지는_409() throws Exception {
        // given
        RefundRequestCommand command = new RefundRequestCommand(BUYER_ID, ORDER_ID, ORDER_ITEM_ID, 1);

        // when
        List<String> results = runTogether(
                () -> refundFacade.request(command),
                () -> refundFacade.request(command));

        // then
        assertThat(results).containsExactlyInAnyOrder(SUCCESS, RefundErrorCode.REFUND_ALREADY_IN_PROGRESS.name());
        assertThat(refundRepository.count()).isOne();
    }

    private static RefundDecisionCommand approve(Long refundId) {
        return new RefundDecisionCommand(refundId, SELLER_ID, RefundDecision.APPROVE, null);
    }

    // 두 작업을 래치로 동시에 출발시키고, 각 결과를 SUCCESS 또는 에러 코드 이름으로 돌려준다
    private static List<String> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return resultOf(task);
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private static String resultOf(Callable<?> task) throws Exception {
        try {
            task.call();
            return SUCCESS;
        } catch (RestApiException e) {
            return e.getErrorCode().name();
        }
    }

    private static String await(Future<String> future) throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(30, TimeUnit.SECONDS);
    }
}
