package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.refund.adapter.out.event.RefundTopics;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.adapter.out.persistence.RefundStepJpaRepository;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundStepPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderCancellationPort;
import com.ok.testsupport.FakeOrderQueryPort;
import com.ok.testsupport.FakeProductStockReleasePort;
import com.ok.testsupport.FakeWalletRefundRequestPort;
import com.ok.testsupport.RefundWalletResultSender;
import com.ok.testsupport.TestAccessTokens;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 재고가 다시 해도 거절되면 지갑 돈을 다시 가져오는(회수, #546) 흐름을 실제 Kafka(테스트 컨테이너)로 끝까지 본다.
// 승인 → 지갑 완료 메시지 → 재고 거절(409) → 회수 줄을 만들고 바로 회수 요청 발행 → 지갑 회수 결과 메시지 → 회수 줄 기록.
// 지갑 코드는 쓰지 않는다. 회수 요청은 실제 토픽에서 읽어 약속한 JSON인지 보고, 지갑 결과는 RefundWalletResultSender가 직접 넣는다.
// 예시: 구매자 1이 판매자 5의 감자(주문 상품 801) 3개를 주문 8000으로 샀다. 10,000원씩, 1개를 취소해 10,000원을 돌려줬는데 상품이 재고 해제를 거절한다
class RefundWalletRevertIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 5L;
    // 다른 환불 통합 테스트(주문 10, 20, 30, 50, 60, 70, 6000)와 겹치지 않는 번호. Long 캐시(-128~127) 밖이라 ID 비교를 != 로 바꾸면 걸린다
    private static final Long ORDER_ID = 8_000L;
    private static final Long ORDER_ITEM_ID = 801L;
    private static final Long PRODUCT_OPTION_ID = 55L;
    private static final long REFUND_AMOUNT = 10_000L;
    // 토픽을 끝까지 읽는 데 기다리는 최대 시간 (보통 1초 안에 끝남)
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestAccessTokens tokens;
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    RefundStepPersistencePort refundStepPersistencePort;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    RefundStepJpaRepository refundStepJpaRepository;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    @Autowired
    FakeOrderCancellationPort orderCancellationPort;
    @Autowired
    FakeProductStockReleasePort productStockReleasePort;
    @Autowired
    FakeWalletRefundRequestPort walletRefundRequestPort;
    @Autowired
    RefundWalletResultSender walletResultSender;
    // 알람이 다음 회차에 부르는 입구. 알람은 테스트 프로필에서 뜨지 않아 직접 부른다
    @Autowired
    RefundSagaUseCase refundSagaUseCase;
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    Clock clock;

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
        // 단계 줄이 환불 행을 외래 키로 가리켜 먼저 지운다
        refundStepJpaRepository.deleteAllInBatch();
        refundJpaRepository.deleteAllInBatch();
        orderQueryPort.clear();
        orderCancellationPort.clear();
        productStockReleasePort.clear();
        walletRefundRequestPort.clear();
    }

    @Test
    void 재고가_거절되면_바로_지갑에_약속한_JSON으로_회수를_요청하고_다시_가져오면_회수_줄을_성공으로_남긴다() throws Exception {
        // given
        Map<TopicPartition, Long> before = endOffsets(RefundTopics.REFUND_WALLET_REVERT_REQUESTED);

        // when: 지갑이 10,000원을 돌려줬는데 상품이 재고 해제를 거절한다. 알람을 부르지 않는다
        Long refundId = refundRefusedByProduct();

        // then: 회수 요청이 바로 한 건 나갔다. 키는 orderId, 값은 PR #537에서 약속한 네 필드
        List<ConsumerRecord<?, ?>> sent = readMessagesOf(RefundTopics.REFUND_WALLET_REVERT_REQUESTED, refundId, before);
        assertThat(sent).hasSize(1);
        ConsumerRecord<?, ?> record = sent.getFirst();
        assertThat(record.key()).isEqualTo(String.valueOf(ORDER_ID));
        JsonNode message = jsonMapper.readTree((String) record.value());
        assertThat(message).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(message.get("refundId").asLong()).isEqualTo(refundId);
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(ORDER_ID);
            softly.assertThat(message.get("buyerId").asLong()).isEqualTo(BUYER_ID);
            softly.assertThat(message.get("amount").asLong()).isEqualTo(REFUND_AMOUNT);
            softly.assertThat(message.propertyNames()).containsExactlyInAnyOrder("refundId", "orderId", "buyerId", "amount");
        });
        assertThat(stepOf(refundId, RefundStepType.WALLET_REVERT).getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);

        // when: 지갑이 다시 가져왔다고 알린다
        walletResultSender.sendReverted(refundId);

        // then: 회수 줄은 성공, 환불은 주문 되돌리기 결과(#622)를 기다리는 동안 처리 중. 멈춘 재고 줄이 있어 멈춘 환불 찾기가 사람에게 알린다
        // (되돌리기 요청과 그 뒤는 RefundOrderRevertIntegrationTest가 본다)
        awaitStepStatus(refundId, RefundStepType.WALLET_REVERT, RefundStepStatus.SUCCEEDED);
        assertThat(refundPersistencePort.findById(refundId).map(Refund::getStatus)).contains(RefundStatus.PROCESSING);
        assertThat(refundStepPersistencePort.findRefundIdsNeedingAttention(LocalDateTime.now(clock))).contains(refundId);
    }

    @Test
    void 지갑이_잔액이_모자라_다시_가져오지_못하면_회수_줄을_그_이유로_멈춘다() throws Exception {
        // given
        Long refundId = refundRefusedByProduct();

        // when
        walletResultSender.sendRevertFailed(refundId, "INSUFFICIENT_BALANCE");

        // then
        awaitStepStatus(refundId, RefundStepType.WALLET_REVERT, RefundStepStatus.STOPPED);
        RefundStep revert = stepOf(refundId, RefundStepType.WALLET_REVERT);
        assertThat(revert.getLastReason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(refundPersistencePort.findById(refundId).map(Refund::getStatus)).contains(RefundStatus.PROCESSING);
    }

    // 회수 결과를 기다리는 중에 알람이 깨워도(결과 대기 시각 전) 지갑 반환도 회수도 다시 보내지 않는다.
    // 계획이 회수 줄을 먼저 보는 순서 자체는 RefundSagaPlanTest와 RefundSagaServiceWalletRevertTest(회수 결과 대기 시간이 지나면 회수를 다시 요청)가 지킨다
    @Test
    void 회수_결과를_기다리는_중에_알람이_깨워도_지갑_반환과_회수를_다시_보내지_않는다() throws Exception {
        // given
        Map<TopicPartition, Long> revertBefore = endOffsets(RefundTopics.REFUND_WALLET_REVERT_REQUESTED);
        Long refundId = refundRefusedByProduct();
        Map<TopicPartition, Long> approvedBefore = endOffsets(RefundTopics.REFUND_APPROVED);

        // when: 회수 결과를 기다리는 중에 알람이 두 번 깨운다
        refundSagaUseCase.resume(refundId);
        refundSagaUseCase.resume(refundId);

        // then
        assertThat(readMessagesOf(RefundTopics.REFUND_APPROVED, refundId, approvedBefore)).isEmpty();
        assertThat(readMessagesOf(RefundTopics.REFUND_WALLET_REVERT_REQUESTED, refundId, revertBefore)).hasSize(1);
        assertThat(stepOf(refundId, RefundStepType.WALLET).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        assertThat(stepOf(refundId, RefundStepType.WALLET_REVERT).getAttemptCount()).isEqualTo(1);
    }

    // 상품 장애로 다시 시도할 실패는 회수하지 않는다(5번을 다 써 멈춰도 회수 안 함, 11/16 결정). 재고 줄은 할 차례로 남는다
    @Test
    void 재고가_일시_장애로_실패하면_회수_줄을_만들지_않는다() throws Exception {
        // given
        Long refundId = approvedRefundId();
        productStockReleasePort.fail();

        // when
        walletResultSender.sendCompleted(refundId);

        // then
        awaitStepStatus(refundId, RefundStepType.PRODUCT, RefundStepStatus.READY);
        assertThat(refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET_REVERT)).isEmpty();
    }

    // 승인 → 지갑 완료 → 재고 거절까지 간 환불. 회수 요청을 바로 보내 회수 줄이 결과 대기가 될 때까지 기다린다
    private Long refundRefusedByProduct() throws Exception {
        Long refundId = approvedRefundId();
        productStockReleasePort.refuse();
        walletResultSender.sendCompleted(refundId);
        awaitStepStatus(refundId, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT);
        RefundStep product = stepOf(refundId, RefundStepType.PRODUCT);
        assertThat(product).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(product.getStatus()).isEqualTo(RefundStepStatus.STOPPED);
            softly.assertThat(product.getLastReason()).isEqualTo("PRODUCT_RELEASE_REFUSED");
        });
        return refundId;
    }

    private Long approvedRefundId() throws Exception {
        String response = mockMvc.perform(post(BUYER_URL)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId": %d, "orderItemId": %d, "quantity": 1}
                                """.formatted(ORDER_ID, ORDER_ITEM_ID)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long refundId = ((Number) JsonPath.read(response, "$.refundId")).longValue();
        mockMvc.perform(patch(SELLER_URL + "/" + refundId)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "BUYER", "SELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.refundAmount", is((int) REFUND_AMOUNT)));
        return refundId;
    }

    private void awaitStepStatus(Long refundId, RefundStepType type, RefundStepStatus expected) {
        RefundWalletResultSender.awaitUntil("환불 " + refundId + "의 " + type + " 줄이 " + expected + "이(가) 되지 않았습니다",
                () -> refundStepPersistencePort.findByRefundIdAndType(refundId, type)
                        .map(step -> step.getStatus() == expected).orElse(false));
    }

    // 그 단계 줄. 없으면 기록이 안 된 것이라 바로 실패한다
    private RefundStep stepOf(Long refundId, RefundStepType type) {
        Optional<RefundStep> step = refundStepPersistencePort.findByRefundIdAndType(refundId, type);
        return step.orElseThrow();
    }

    // ---------- 도우미: 토픽 읽기 (RefundWalletRequestIntegrationTest와 같은 방식) ----------

    // from 위치부터 토픽 끝까지 읽어, 값의 refundId가 이번 환불인 메시지만 모은다
    private List<ConsumerRecord<?, ?>> readMessagesOf(String topic, Long refundId, Map<TopicPartition, Long> from)
            throws Exception {
        try (Consumer<?, ?> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignTopic(consumer, topic);
            consumer.seekToBeginning(partitions);
            for (Map.Entry<TopicPartition, Long> entry : from.entrySet()) {
                consumer.seek(entry.getKey(), entry.getValue());
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<ConsumerRecord<?, ?>> found = new ArrayList<>();
            long deadline = System.nanoTime() + READ_TIMEOUT.toNanos();
            while (!reachedEnd(consumer, endOffsets) && System.nanoTime() < deadline) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode value = jsonMapper.readTree((String) record.value());
                    if (refundId.equals(value.get("refundId").asLong())) {
                        found.add(record);
                    }
                }
            }
            return found;
        }
    }

    private Map<TopicPartition, Long> endOffsets(String topic) {
        try (Consumer<?, ?> consumer = createConsumer()) {
            return consumer.endOffsets(assignTopic(consumer, topic));
        }
    }

    private Consumer<?, ?> createConsumer() {
        Properties overrides = new Properties();
        // JSON을 클래스로 바꾸지 않고 글자 그대로 읽는다
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 다른 컨슈머 그룹의 읽은 위치와 섞이지 않게 매번 새 그룹으로 읽는다
        String groupId = "refund-wallet-revert-test-" + UUID.randomUUID();
        return consumerFactory.createConsumer(groupId, null, null, overrides);
    }

    private List<TopicPartition> assignTopic(Consumer<?, ?> consumer, String topic) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : consumer.partitionsFor(topic)) {
            partitions.add(new TopicPartition(info.topic(), info.partition()));
        }
        consumer.assign(partitions);
        return partitions;
    }

    private boolean reachedEnd(Consumer<?, ?> consumer, Map<TopicPartition, Long> endOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }
}
