package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.common.exception.RestApiException;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStepType;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderCancellationPort;
import com.ok.testsupport.FakeOrderQueryPort;
import com.ok.testsupport.FakeProductStockReleasePort;
import com.ok.testsupport.RefundWalletResultSender;
import com.ok.testsupport.TestAccessTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExceptionMatcher;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 지갑이 실제 Kafka(테스트 컨테이너)로 보낸 예치금 반환 결과(완료·실패)를 환불 리스너가 받아 처리하는지 확인한다 (#544)
// - 토픽 이름·컨슈머 그룹·컨테이너 팩토리·타입 헤더 없는 JSON 읽기(spring.json.value.default.type) 설정이 실제로 맞물리는지 끝까지 본다
//   (서비스·리스너 단위 테스트는 이 어노테이션 설정을 거치지 않는다)
// - 지갑 코드는 쓰지 않고 지갑이 보낼 JSON을 RefundWalletResultSender가 직접 토픽에 넣는다. 리스너는 별도 스레드에서 받으므로 결과는 기다린 뒤 본다
// 예시: 구매자 1이 판매자 5의 감자(주문 상품 601) 3개를 주문 6000으로 샀다. 10,000원씩, 배송비 3,000원을 냈고 1개를 취소해 10,000원을 돌려준다
class RefundWalletResultIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 5L;
    // 다른 환불 통합 테스트(주문 10, 20, 21, 30, 40, 50)와 겹치지 않는 번호.
    // Long 캐시(-128~127) 밖이어야 주문 대조를 실수로 != 로 바꿨을 때 실제 경로에서 걸린다(캐시 안 번호는 값이 같으면 같은 객체다)
    private static final Long ORDER_ID = 6_000L;
    private static final Long ORDER_ITEM_ID = 601L;
    private static final Long PRODUCT_OPTION_ID = 55L;

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestAccessTokens tokens;
    // 확인은 출력 포트(도메인)로 한다. 테이블 비우기는 포트에 없는 테스트 전용 작업이라 JPA 리포지토리로 한다
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    @Autowired
    FakeOrderCancellationPort orderCancellationPort;
    @Autowired
    FakeProductStockReleasePort productStockReleasePort;
    @Autowired
    RefundWalletResultSender walletResultSender;
    @Autowired
    KafkaListenerEndpointRegistry listenerRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 3)));
        orderQueryPort.put(new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, PRODUCT_OPTION_ID, "감자", "1kg",
                "PREPARING", 10_000L, 3, bundle));
    }

    @AfterEach
    void tearDown() {
        refundJpaRepository.deleteAllInBatch();
        orderQueryPort.clear();
        orderCancellationPort.clear();
        productStockReleasePort.clear();
    }

    @Test
    void 승인하면_처리_중이고_지갑_완료_메시지를_받으면_재고를_되돌리고_완료한다() throws Exception {
        // given
        Long refundId = requestedRefundId();

        // when: 승인한다
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.refundAmount", is(10000)));

        // then: 지갑 결과를 기다리는 동안 재고는 되돌리지 않는다
        assertThat(productStockReleasePort.releasedOf(refundId)).isEmpty();
        assertThat(refundPersistencePort.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSING);

        // when: 지갑이 완료 메시지를 보낸다
        walletResultSender.completeByWallet(refundId);

        // then
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(sut.getCompletedAt()).isNotNull();
            // 끝났으므로 진행 중 칸을 비운다
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
            softly.assertThat(sut.getFailedStep()).isNull();
        });
        assertThat(productStockReleasePort.releaseCallCountOf(refundId)).isEqualTo(1);
        assertThat(productStockReleasePort.releasedOf(refundId).orElseThrow().productOptionId()).isEqualTo(PRODUCT_OPTION_ID);
    }

    @Test
    void 지갑_실패_메시지를_받으면_처리_중으로_남고_지갑_단계_실패와_이유를_기록한다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        approve(refundId).andExpect(status().isOk());
        String reason = "INSUFFICIENT_SELLER_BALANCE";

        // when
        walletResultSender.sendFailed(refundId, reason);

        // then: 실패 기록이 남을 때까지 기다린다
        RefundWalletResultSender.awaitUntil("실패 이유가 기록되지 않았습니다",
                () -> reason.equals(refundPersistencePort.findById(refundId).orElseThrow().getFailureCode()));
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(sut.getFailedStep()).isEqualTo(RefundStepType.WALLET);
            softly.assertThat(sut.getFailureCode()).isEqualTo(reason);
        });
        // 돈이 안 돌아갔으니 재고도 되돌리지 않는다
        assertThat(productStockReleasePort.releasedOf(refundId)).isEmpty();
    }

    // 같은 완료 메시지가 두 번 오는 것은 Kafka가 최소 한 번 전달을 보장하기 때문에 생긴다(리밸런스·재시도)
    @Test
    void 같은_완료_메시지를_두_번_받아도_재고는_한_번만_되돌리고_완료로_남는다() throws Exception {
        // given: 첫 완료 메시지가 처리돼 완료됐다
        Long refundId = requestedRefundId();
        approve(refundId).andExpect(status().isOk());
        walletResultSender.completeByWallet(refundId);
        Refund firstDone = refundPersistencePort.findById(refundId).orElseThrow();

        // when: 같은 메시지를 다시 보내고, 뒤이어 다른 환불의 완료 메시지를 보낸다
        walletResultSender.sendCompleted(refundId);
        Long laterRefundId = approvedAfterSecondRequest(refundId);
        walletResultSender.completeByWallet(laterRefundId);

        // then: 테스트 브로커는 토픽 파티션이 하나라 메시지가 보낸 순서대로 처리된다. 뒤 환불이 완료됐으면 중복 메시지도 이미 처리된 것이다
        Refund sut = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.COMPLETED);
            softly.assertThat(sut.getCompletedAt()).isEqualTo(firstDone.getCompletedAt());
        });
        // 호출 횟수로 확인하는 이유: Fake는 같은 refundId면 첫 값을 유지해서, 결과만 봐서는 한 번만 되돌렸는지 알 수 없다
        assertThat(productStockReleasePort.releaseCallCountOf(refundId)).isEqualTo(1);
    }

    // 재고 복구 실패 경로를 실제 리스너 스레드로 확인한다. 서비스 단위 테스트는 트랜잭션을 실제로 묶지 않고(NoOpTransactionManager)
    // 오류 처리기도 거치지 않아, "재고 호출이 트랜잭션 밖이라 PRODUCT 기록이 따로 커밋된다"와 "오류 처리기가 다시 시도해 복구 뒤 완료한다"를 못 본다
    @Test
    void 지갑_완료_뒤_재고_복구가_실패하면_PRODUCT_실패를_남기고_다시_시도해_복구되면_완료한다() throws Exception {
        // given: 상품 서버가 실패하는 동안 지갑 완료 메시지가 온다
        Long refundId = requestedRefundId();
        approve(refundId).andExpect(status().isOk());
        productStockReleasePort.fail();

        // when
        walletResultSender.sendCompleted(refundId);

        // then: 재고 실패가 기록되고 처리 중으로 남는다(다시 시도는 1·2·4초... 간격이라 그 사이에 본다)
        RefundWalletResultSender.awaitUntil("재고 복구 실패가 기록되지 않았습니다",
                () -> refundPersistencePort.findById(refundId).orElseThrow().getFailedStep() == RefundStepType.PRODUCT);
        Refund failed = refundPersistencePort.findById(refundId).orElseThrow();
        assertThat(failed).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(failed.getStatus()).isEqualTo(RefundStatus.PROCESSING);
            softly.assertThat(failed.getFailureCode()).isEqualTo("PRODUCT_API_FAILED");
            softly.assertThat(failed.getCompletedAt()).isNull();
        });
        assertThat(productStockReleasePort.releasedOf(refundId)).isEmpty();

        // when: 상품 서버가 살아난다. 메시지를 다시 보내지 않아도 환불 오류 처리기가 같은 메시지를 다시 시도한다
        productStockReleasePort.clear();

        // then
        walletResultSender.awaitStatus(refundId, RefundStatus.COMPLETED);
        assertThat(productStockReleasePort.releasedOf(refundId).orElseThrow().productOptionId()).isEqualTo(PRODUCT_OPTION_ID);
    }

    // 지갑 결과 리스너가 환불 전용 팩토리(오류 처리기)를 실제로 쓰는지 본다. 설정 단위 테스트는 처리기를 따로 만들어 보므로,
    // 팩토리에 처리기를 붙이지 않거나 리스너가 containerFactory를 빼먹어도(기본 팩토리 = 간격 없이 10번, 값 오류도 다시 시도) 통과한다
    @Test
    void 지갑_결과_리스너는_환불_오류_처리기를_쓴다() {
        // given: 두 토픽을 듣는 컨테이너를 찾는다
        List<MessageListenerContainer> containers = new ArrayList<>();
        for (MessageListenerContainer container : listenerRegistry.getAllListenerContainers()) {
            String[] topics = container.getContainerProperties().getTopics();
            if (topics == null) {
                continue;
            }
            List<String> topicList = Arrays.asList(topics);
            if (topicList.contains(RefundWalletResultSender.COMPLETED_TOPIC) || topicList.contains(RefundWalletResultSender.FAILED_TOPIC)) {
                containers.add(container);
            }
        }
        assertThat(containers).hasSize(2);

        for (MessageListenerContainer container : containers) {
            // when
            CommonErrorHandler errorHandler = ((AbstractMessageListenerContainer<?, ?>) container).getCommonErrorHandler();

            // then: 처리기가 있고, 값 오류는 넘기고 일시적 실패는 다시 시도하는 환불 규칙을 쓴다(match가 true = 다시 시도)
            assertThat(errorHandler).isInstanceOf(DefaultErrorHandler.class);
            ExceptionMatcher sut = ReflectionTestUtils.invokeMethod(errorHandler, "getExceptionMatcher");
            assertThat(sut).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(sut.match(new IllegalArgumentException("값이 틀림"))).isFalse();
                softly.assertThat(sut.match(new RestApiException(RefundErrorCode.PRODUCT_API_FAILED))).isTrue();
            });

            // then: 다시 시도 간격도 1·2·4·8·16초 다음에 멈춘다. 분류만 보면 간격 없이 바로 10번 하는 처리기로 바뀌어도 통과해,
            // 몇 초 끊김에도 지갑 결과를 버리는 문제를 못 잡는다. Spring Kafka가 간격 설정을 꺼내 주지 않아 내부 이름으로 꺼낸다
            // (라이브러리를 올려 이름이 바뀌면 여기서 실패로 알려 준다)
            Object failureTracker = ReflectionTestUtils.invokeMethod(errorHandler, "getFailureTracker");
            assertThat(failureTracker).isNotNull();
            BackOff backOff = (BackOff) ReflectionTestUtils.getField(failureTracker, "backOff");
            assertThat(backOff).isNotNull();
            BackOffExecution execution = backOff.start();
            List<Long> intervals = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                intervals.add(execution.nextBackOff());
            }
            assertThat(intervals).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, BackOffExecution.STOP);
        }
    }

    // 첫 환불이 완료돼 진행 중 칸이 비었으므로 같은 상품(남은 2개 중 1개)을 다시 요청해 승인한다
    private Long approvedAfterSecondRequest(Long firstRefundId) throws Exception {
        Long second = requestedRefundId();
        assertThat(second).isNotEqualTo(firstRefundId);
        approve(second).andExpect(status().isOk());
        return second;
    }

    private Long requestedRefundId() throws Exception {
        String response = mockMvc.perform(post(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "orderItemId": %d, "quantity": 1}
                                """.formatted(ORDER_ID, ORDER_ITEM_ID)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.refundId")).longValue();
    }

    private ResultActions approve(Long refundId) throws Exception {
        return mockMvc.perform(patch(SELLER_URL + "/" + refundId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "BUYER", "SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"decision": "APPROVE"}
                        """));
    }
}
