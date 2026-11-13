package com.ok.refund.application.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.in.RefundDecision;
import com.ok.refund.application.port.in.RefundDecisionCommand;
import com.ok.refund.application.port.in.RefundRequestCommand;
import com.ok.refund.application.port.in.RequestRefundUseCase;
import com.ok.refund.application.port.in.WithdrawRefundUseCase;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderQueryPort;
import com.ok.testsupport.FakeProductStockReleasePort;
import com.ok.testsupport.RefundWalletResultSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.Query;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 같은 판매자 묶음의 서로 다른 환불을 동시에 승인해도 배송비를 한 번만 돌려주고 한 번만 빼는지 실제 DB 잠금으로 확인한다(#411).
// 실행마다 어느 쪽이 먼저 잠금을 얻을지 달라지므로 여러 번 돌려 두 순서를 모두 거치게 한다
class RefundConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final String SUCCESS = "SUCCESS";
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 7L;
    private static final Long ORDER_ID = 50L;
    private static final Long A = 501L;
    private static final Long B = 502L;
    private static final Long C = 503L;

    @Autowired
    RequestRefundUseCase requestRefundUseCase;
    @Autowired
    WithdrawRefundUseCase withdrawRefundUseCase;
    // 바깥 트랜잭션 검사는 주문 정보를 받은 뒤의 승인 구간(패키지 안 approve)을 직접 불러 보므로 구현 클래스로 받는다
    @Autowired
    DecideRefundService decideRefundService;
    // 조회 확인은 출력 포트(도메인)로 한다. 테이블 비우기는 포트에 없는 테스트 전용 작업이라 JPA 리포지토리로 한다
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    Clock clock;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    @Autowired
    FakeProductStockReleasePort productStockReleasePort;
    // 지갑 대신 완료 메시지를 실제 Kafka로 보낸다(#544)
    @Autowired
    RefundWalletResultSender walletResultSender;

    @AfterEach
    void tearDown() {
        refundJpaRepository.deleteAllInBatch();
        orderQueryPort.clear();
        productStockReleasePort.clear();
    }

    @RepeatedTest(5)
    void 같은_묶음을_동시에_승인해도_배송비는_마지막_한_요청만_받는다() throws Exception {
        // given: A 10,000 + B 10,000 = 20,000원(무료배송 기준 50,000원 미만). 배송비 3,000원과 도서산간 운임 2,000원을 냈다(총 25,000원)
        BundleShipping bundle = new BundleShipping(3_000L, 2_000L, 3_000L, 50_000L,
                List.of(new BundleItem(A, 10_000L, 1), new BundleItem(B, 10_000L, 1)));
        orderQueryPort.put(orderItem(A, 10_000L, bundle));
        orderQueryPort.put(orderItem(B, 10_000L, bundle));
        Long refundA = request(A);
        Long refundB = request(B);

        // when
        List<String> results = runTogether(() -> approve(refundA), () -> approve(refundB));

        // then: 둘이 서로를 "아직 남음"으로 보면 아무도 배송비를 못 받아 20,000원이 된다
        assertThat(results).containsOnly(SUCCESS);
        List<Long> feesPerRefund = new ArrayList<>();
        long total = 0;
        for (Long refundId : List.of(refundA, refundB)) {
            Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
            feesPerRefund.add(refund.getShippingFeeAmount() + refund.getRemoteAreaFeeAmount());
            total += refund.getRefundAmount();
        }
        long refundedTotal = total;
        assertSoftly(softly -> {
            softly.assertThat(feesPerRefund).containsExactlyInAnyOrder(5_000L, 0L);
            softly.assertThat(refundedTotal).isEqualTo(25_000L);
        });
    }

    @RepeatedTest(5)
    void 같은_묶음의_일부_취소를_동시에_승인해도_무료배송이_깨질_때_배송비는_한_번만_뺀다() throws Exception {
        // given: A 30,000 + B 15,000 + C 15,000 = 60,000원 무료배송(기준 50,000원, 기본 배송비 3,000원).
        // B·C를 각각 요청하면 먼저 승인되는 쪽이 45,000원으로 기준을 깨 3,000원을 빼고, 나중 쪽은 이미 깨져 빼지 않는다
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 50_000L,
                List.of(new BundleItem(A, 30_000L, 1), new BundleItem(B, 15_000L, 1), new BundleItem(C, 15_000L, 1)));
        orderQueryPort.put(orderItem(B, 15_000L, bundle));
        orderQueryPort.put(orderItem(C, 15_000L, bundle));
        Long refundB = request(B);
        Long refundC = request(C);

        // when
        List<String> results = runTogether(() -> approve(refundB), () -> approve(refundC));

        // then: 둘 다 "처음 깨짐"으로 보면 3,000원을 두 번 뺀다
        assertThat(results).containsOnly(SUCCESS);
        List<Long> deductedPerRefund = new ArrayList<>();
        for (Long refundId : List.of(refundB, refundC)) {
            deductedPerRefund.add(refundPersistencePort.findById(refundId).orElseThrow().getDeductedShippingFeeAmount());
        }
        assertThat(deductedPerRefund).containsExactlyInAnyOrder(3_000L, 0L);
    }

    // 셋 이상이 동시에 승인해도 잠금 순서가 같아 교착 상태 없이 한 줄로 서고, 배송비는 마지막 한 건만 받는다
    @RepeatedTest(5)
    void 같은_묶음_셋을_동시에_승인해도_교착_상태_없이_배송비는_한_요청만_받는다() throws Exception {
        // given: A·B·C 각 10,000원 = 30,000원(기준 50,000원 미만). 배송비 3,000원과 도서산간 운임 2,000원을 냈다(총 35,000원)
        BundleShipping bundle = new BundleShipping(3_000L, 2_000L, 3_000L, 50_000L,
                List.of(new BundleItem(A, 10_000L, 1), new BundleItem(B, 10_000L, 1), new BundleItem(C, 10_000L, 1)));
        orderQueryPort.put(orderItem(A, 10_000L, bundle));
        orderQueryPort.put(orderItem(B, 10_000L, bundle));
        orderQueryPort.put(orderItem(C, 10_000L, bundle));
        Long refundA = request(A);
        Long refundB = request(B);
        Long refundC = request(C);

        // when
        List<String> results = runTogether(() -> approve(refundA), () -> approve(refundB), () -> approve(refundC));

        // then: 교착 상태면 500 예외로 결과를 못 받는다
        assertThat(results).containsOnly(SUCCESS);
        List<Long> feesPerRefund = new ArrayList<>();
        long total = 0;
        for (Long refundId : List.of(refundA, refundB, refundC)) {
            Refund refund = refundPersistencePort.findById(refundId).orElseThrow();
            feesPerRefund.add(refund.getShippingFeeAmount() + refund.getRemoteAreaFeeAmount());
            total += refund.getRefundAmount();
        }
        long refundedTotal = total;
        assertSoftly(softly -> {
            softly.assertThat(feesPerRefund).containsExactlyInAnyOrder(5_000L, 0L, 0L);
            softly.assertThat(refundedTotal).isEqualTo(35_000L);
        });
    }

    // 끝난 행(거절·철회)은 다시 처리 중이 될 수 없어 잠그지 않는다. 다만 이번 환불은 끝났어도 넣어야 잠근 뒤 409로 막힌다.
    // 저장소를 따로 테스트하지 않는 규칙(testing-strategy.md)의 예외다. 어떤 행을 잠갔는지는 승인 결과에 드러나지 않아
    // 서비스 경로로는 끝난 행이 빠졌는지 확인할 수 없고, 잠금 범위가 #411의 핵심 동작이라 쿼리 결과를 직접 본다.
    // 결과가 ID 오름차순인지도 본다. B를 승인해 상태 글자 순서(PROCESSING < REQUESTED)를 ID 순서와 반대로 만들어 두면,
    // DB가 판매자 인덱스(seller_id, status, created_at)로 읽을 때는 정렬이 빠지면 B가 A보다 먼저 나와 실패한다.
    // 다만 어느 인덱스를 탈지는 옵티마이저가 실행마다 정해서, (order_id, seller_id) 인덱스를 타면 정렬 없이도 ID 순서로 나와 통과한다
    // (#486에서 order by를 지우고 반복 실행해 보니 일부만 실패). 정렬이 빠지는 것을 늘 잡는 검사는 아래 쿼리 글자 테스트가 맡는다
    @Test
    void 승인_때_잠글_행은_진행_중_행과_이번_환불뿐이고_ID_오름차순이다() {
        // given: A 요청됨, B 철회 뒤 다시 요청하고 승인(철회 행 + 처리 중 행), C 철회
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(A, 10_000L, 1), new BundleItem(B, 10_000L, 1), new BundleItem(C, 10_000L, 1)));
        orderQueryPort.put(orderItem(A, 10_000L, bundle));
        orderQueryPort.put(orderItem(B, 10_000L, bundle));
        orderQueryPort.put(orderItem(C, 10_000L, bundle));
        Long refundA = request(A);
        Long withdrawnB = request(B);
        withdrawRefundUseCase.withdraw(withdrawnB, BUYER_ID);
        Long refundB = request(B);
        approveAndStopBeforeCompletion(refundB);
        Long withdrawnC = request(C);
        withdrawRefundUseCase.withdraw(withdrawnC, BUYER_ID);
        List<RefundStatus> active = List.of(RefundStatus.REQUESTED, RefundStatus.PROCESSING);

        // when
        List<Long> sut = refundPersistencePort.findLockTargetIds(ORDER_ID, SELLER_ID, withdrawnC, active);

        // then: 철회된 B는 빠지고, 철회됐어도 이번 환불인 C는 들어간다. 상태와 상관없이 ID 오름차순이다
        assertThat(sut).containsExactly(refundA, refundB, withdrawnC);
    }

    // 잠금 대상은 ID 오름차순으로 돌려줘야 동시 승인들이 같은 순서로 잠가 교착 상태가 생기지 않는다(#411).
    // 실제 조회로는 옵티마이저가 고르는 인덱스에 따라 정렬이 빠져도 우연히 ID 순서가 나올 수 있어(위 테스트 주석),
    // 정렬 조건이 쿼리에서 빠지거나 다른 칸으로 바뀌는 것은 쿼리 글자로 막는다(#486).
    // 정렬 칸이 늘어 r.id가 마지막이 아니게 되면(예: order by r.status, r.id) 잠금 순서가 상태에 따라 달라지므로 끝이 r.id인지 본다
    @Test
    void 잠금_대상_조회는_ID_오름차순으로만_정렬한다() throws NoSuchMethodException {
        // given
        Query query = RefundJpaRepository.class
                .getMethod("findLockTargetIds", Long.class, Long.class, Long.class, Collection.class)
                .getAnnotation(Query.class);

        // when
        String sut = query.value().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);

        // then
        assertThat(sut).endsWith("order by r.id");
    }

    // 위의 동시 승인 테스트들은 동시에 출발시킬 뿐이라 어느 쪽이 먼저 잠글지 정할 수 없다. 여기서는 순서를 고정한다:
    // 다른 트랜잭션이 묶음을 잠그고 A를 처리 중으로 바꾼 채 커밋하지 않은 동안 B를 승인하면, B는 잠금에서 기다렸다가
    // 커밋 뒤의 최신 값(A 처리 중)을 보고 마지막 환불로 배송비를 받아야 한다
    @Test
    void 앞_승인이_묶음을_잠근_동안_뒤_승인은_기다렸다가_최신_값으로_판단한다() throws Exception {
        // given: 배송비 3,000원과 도서산간 운임 2,000원을 낸 묶음 A 10,000 + B 10,000
        BundleShipping bundle = new BundleShipping(3_000L, 2_000L, 3_000L, 50_000L,
                List.of(new BundleItem(A, 10_000L, 1), new BundleItem(B, 10_000L, 1)));
        orderQueryPort.put(orderItem(A, 10_000L, bundle));
        orderQueryPort.put(orderItem(B, 10_000L, bundle));
        Long refundA = request(A);
        Long refundB = request(B);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate first = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holding = executor.submit(() -> first.executeWithoutResult(status -> {
                Refund lockedA = refundPersistencePort.findByIdForUpdate(refundA).orElseThrow();
                refundPersistencePort.findByIdForUpdate(refundB).orElseThrow();
                lockedA.approve(LocalDateTime.of(2026, 11, 5, 10, 0), RefundShippingFee.none());
                // 명시적 저장(#481): update를 불러야 커밋 때 A의 "처리 중"이 저장돼 B가 최신 값으로 읽는다
                refundPersistencePort.update(lockedA);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            // when: 앞 트랜잭션이 잠금을 쥔 동안 B를 승인한다
            Future<String> approvingB = executor.submit(() -> resultOf(() -> approve(refundB)));

            // then: 잠금 때문에 끝나지 못하고 기다린다
            assertThatThrownBy(() -> approvingB.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
            assertThat(approvingB.get(30, TimeUnit.SECONDS)).isEqualTo(SUCCESS);
        }
        Refund savedB = refundPersistencePort.findById(refundB).orElseThrow();
        assertThat(savedB).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(savedB.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(savedB.getRemoteAreaFeeAmount()).isEqualTo(2_000L);
            softly.assertThat(savedB.getRefundAmount()).isEqualTo(15_000L);
        });
    }

    // 완료로 바꿀 때는 자기 행만 잠근다(#414). 처리 중과 완료를 둘 다 "이미 취소된 것"으로 세서, 바꾸는 중에 같은 묶음의 다른 승인이 와도
    // 그 승인의 판단이 달라지지 않는다는 전제다. 여기서는 완료 트랜잭션이 감자 행을 잠근 채 커밋하지 않은 동안 배추를 승인해, 배추가 잠금에서
    // 기다렸다가 완료된 감자를 취소된 것으로 세는지 본다. 완료 트랜잭션은 DecideRefundService.complete와 같은 일(잠금 조회 → complete → update)을
    // 직접 한다. 서비스 안의 완료 구간을 원하는 때에 멈출 방법이 없어서다
    @Test
    void 감자를_완료로_바꾸는_동안_배추를_승인해도_기다렸다가_완료된_감자를_세어_배송비를_돌려준다() throws Exception {
        // given: 감자(A) 10,000 + 배추(B) 25,000 = 35,000원 무료배송(기준 30,000원, 기본 배송비 3,000원, 낸 배송비 0원)
        BundleShipping bundle = new BundleShipping(0, 0, 3_000L, 30_000L,
                List.of(new BundleItem(A, 10_000L, 1), new BundleItem(B, 25_000L, 1)));
        orderQueryPort.put(orderItem(A, 10_000L, bundle));
        orderQueryPort.put(orderItem(B, 25_000L, bundle));
        Long potato = request(A);
        Long cabbage = request(B);
        // 감자를 승인하면 남은 25,000원이 기준 아래라 3,000원을 빼고 7,000원을 돌려준다. 재고 단계에서 멈춰 처리 중으로 둔다
        approveAndStopBeforeCompletion(potato);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate completing = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> holding = executor.submit(() -> completing.executeWithoutResult(status -> {
                Refund lockedPotato = refundPersistencePort.findByIdForUpdate(potato).orElseThrow();
                lockedPotato.complete(LocalDateTime.now(clock));
                refundPersistencePort.update(lockedPotato);
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            // when: 감자 완료가 커밋되기 전에 배추를 승인한다
            Future<String> approvingCabbage = executor.submit(() -> resultOf(() -> approve(cabbage)));

            // then: 배추 승인은 진행 중인 감자 행을 잠그려다 기다린다
            assertThatThrownBy(() -> approvingCabbage.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
            assertThat(approvingCabbage.get(30, TimeUnit.SECONDS)).isEqualTo(SUCCESS);
        }
        // 배추 승인은 처리 중으로 끝난다. 지갑이 반환을 마쳤다는 메시지가 와야 완료된다
        walletResultSender.completeByWallet(cabbage);
        Refund savedPotato = refundPersistencePort.findById(potato).orElseThrow();
        Refund savedCabbage = refundPersistencePort.findById(cabbage).orElseThrow();
        assertThat(savedCabbage).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(savedPotato.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(savedPotato.getRefundAmount()).isEqualTo(7_000L);
            softly.assertThat(savedCabbage.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            // 완료된 감자를 세면 묶음 전부 취소라 앞에서 뺀 3,000원을 돌려준다: 25,000 + 3,000 = 28,000원.
            // 완료를 세지 않으면 감자가 남은 것으로 보여 3,000원을 또 빼 22,000원이 된다
            softly.assertThat(savedCabbage.getShippingFeeAmount()).isEqualTo(3_000L);
            softly.assertThat(savedCabbage.getRefundAmount()).isEqualTo(28_000L);
        });
    }

    // 바깥 트랜잭션에 합류하면 위의 동시 승인 테스트들이 지키는 동작이 조용히 깨진다. 바깥이 기본 격리 수준이면 READ COMMITTED가
    // 무시되고, 바깥이 READ COMMITTED여도 바깥에서 먼저 읽은 옛 엔티티로 판단할 수 있다. 그래서 격리 수준과 상관없이 바로 실패해야 한다
    @ParameterizedTest
    @MethodSource("바깥_트랜잭션_격리_수준")
    void 다른_트랜잭션_안에서_승인하면_격리_수준과_상관없이_바로_실패하고_바꾸지_않는다(int isolationLevel) {
        // given
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L, List.of(new BundleItem(A, 10_000L, 1)));
        RefundableOrderItem item = orderItem(A, 10_000L, bundle);
        orderQueryPort.put(item);
        Long refundId = request(A);
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(isolationLevel);

        // when & then
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> decideRefundService.approve(refundId, SELLER_ID, item)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("환불 승인은 이미 열린 트랜잭션 안에서 부를 수 없습니다. 새 트랜잭션으로 실행해야 합니다.");
        assertThat(refundPersistencePort.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    // 승인 검사(새 트랜잭션인지)는 영속성 컨텍스트도 새것이라는 전제에 기댄다. OSIV(open-in-view)를 켜면 요청 하나 동안 영속성 컨텍스트가
    // 이어져 승인 전 확인 조회가 앞서 읽은 환불이 승인까지 남고, 잠금 조회가 그 옛 객체를 돌려줘도 검사로 막지 못한다. 설정이 바뀌면 여기서 드러나게 한다
    @Test
    void OSIV가_꺼져_있어_승인이_새_영속성_컨텍스트로_시작한다() {
        // when
        String[] sut = applicationContext.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class);

        // then
        assertThat(sut).isEmpty();
    }

    static Stream<Arguments> 바깥_트랜잭션_격리_수준() {
        return Stream.of(
                Arguments.of(Named.of("기본 격리 수준", TransactionDefinition.ISOLATION_DEFAULT)),
                Arguments.of(Named.of("READ COMMITTED", TransactionDefinition.ISOLATION_READ_COMMITTED)));
    }

    private Long request(Long orderItemId) {
        return requestRefundUseCase.request(new RefundRequestCommand(BUYER_ID, ORDER_ID, orderItemId, 1, null)).refundId();
    }

    private void approve(Long refundId) {
        decideRefundService.decide(new RefundDecisionCommand(refundId, SELLER_ID, RefundDecision.APPROVE, null));
    }

    // 승인해서 처리 중에 멈춘 환불을 만든다. 돌려줄 돈이 있으면 승인은 지갑 요청까지만 하고, 완료는 지갑 완료 메시지가 와야 한다(#544).
    // 완료된 행은 끝난 상태라 잠금 대상이 아니어서, "처리 중 행은 잠근다"를 보려면 완료 전에 멈춘 행이 필요하다
    private void approveAndStopBeforeCompletion(Long refundId) {
        approve(refundId);
    }

    private static RefundableOrderItem orderItem(Long orderItemId, long unitPrice, BundleShipping bundle) {
        return new RefundableOrderItem(ORDER_ID, orderItemId, BUYER_ID, SELLER_ID, 77L, "상품", "옵션",
                RefundableOrderItem.CANCELLABLE_STATUS, unitPrice, 1, bundle);
    }

    // 작업들을 래치로 동시에 출발시키고, 각 결과를 SUCCESS 또는 에러 코드 이름으로 돌려준다
    private static List<String> runTogether(Runnable... tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.length)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                Callable<String> waiting = () -> {
                    start.await();
                    return resultOf(task);
                };
                futures.add(executor.submit(waiting));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    // 람다 안에서 기다리려고 InterruptedException을 바꿔 던진다
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 래치를 30초 안에 풀지 못했습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String resultOf(Runnable task) {
        try {
            task.run();
            return SUCCESS;
        } catch (RestApiException e) {
            return e.getErrorCode().name();
        }
    }
}
