package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.refund.adapter.out.event.RefundTopics;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.adapter.out.persistence.RefundStepJpaRepository;
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
import com.ok.testsupport.RefundOrderResultSender;
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
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 승인 뒤 단계가 끝내 실패하면 주문 취소 기록을 되돌리는(#622) 흐름을 실제 Kafka(테스트 컨테이너)로 끝까지 본다.
// 1. 지갑 반환 실패 → 되돌리기 요청 발행 → 주문 되돌림 → 환불 실패로 끝, 같은 상품 다시 요청 가능
// 2. 재고 거절 → 지갑 회수 성공 → 되돌리기 요청 발행 → 주문 되돌리기 실패(이미 구매확정) → 처리 중으로 남아 사람이 봄
// 주문 코드는 쓰지 않는다. 되돌리기 요청은 실제 토픽에서 읽어 약속한 JSON인지 보고, 주문 결과는 RefundOrderResultSender가 직접 넣는다.
// 예시: 구매자 1이 판매자 5의 감자(주문 상품 901) 3개를 주문 9000으로 샀다. 10,000원씩, 1개를 취소해 10,000원을 돌려주려 한다
class RefundOrderRevertIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 5L;
    // 다른 환불 통합 테스트(주문 10, 20, 30, 50, 60, 70, 6000, 8000)와 겹치지 않는 번호. Long 캐시(-128~127) 밖이다
    private static final Long ORDER_ID = 9_000L;
    private static final Long ORDER_ITEM_ID = 901L;
    private static final Long PRODUCT_OPTION_ID = 55L;
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
    @Autowired
    RefundOrderResultSender orderResultSender;
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
    void 지갑이_반환에_실패하면_주문에_약속한_JSON으로_되돌리기를_요청하고_되돌리면_환불을_실패로_끝낸다() throws Exception {
        // given
        Long refundId = approvedRefundId();
        Map<TopicPartition, Long> before = endOffsets(RefundTopics.REFUND_ORDER_REVERT_REQUESTED);

        // when: 지갑이 반환에 실패했다고 알린다
        walletResultSender.sendFailed(refundId, "PAYMENT_NOT_FOUND");

        // then: 지갑 줄은 그 이유로 멈추고, 되돌리기 요청이 바로 한 건 나갔다. 키는 orderId, 값은 PR #537에서 약속한 네 필드
        awaitStepStatus(refundId, RefundStepType.ORDER_REVERT, RefundStepStatus.AWAITING_RESULT);
        assertThat(stepOf(refundId, RefundStepType.WALLET).getLastReason()).isEqualTo("PAYMENT_NOT_FOUND");
        List<ConsumerRecord<?, ?>> sent = readMessagesOf(RefundTopics.REFUND_ORDER_REVERT_REQUESTED, refundId, before);
        assertThat(sent).hasSize(1);
        ConsumerRecord<?, ?> record = sent.getFirst();
        assertThat(record.key()).isEqualTo(String.valueOf(ORDER_ID));
        JsonNode message = jsonMapper.readTree((String) record.value());
        assertThat(message).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(message.get("refundId").asLong()).isEqualTo(refundId);
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(ORDER_ID);
            softly.assertThat(message.get("orderItemId").asLong()).isEqualTo(ORDER_ITEM_ID);
            softly.assertThat(message.get("quantity").asInt()).isEqualTo(1);
            softly.assertThat(message.propertyNames()).containsExactlyInAnyOrder("refundId", "orderId", "orderItemId", "quantity");
        });

        // when: 주문이 취소 기록을 되돌렸다고 알린다
        orderResultSender.sendReverted(refundId);

        // then: 환불은 실패로 끝나고 되돌리기 줄은 성공. 진행 중 칸이 비어 같은 상품을 다시 요청할 수 있다
        // 환불 상태를 기다리는 도우미는 지갑 결과 도우미에 하나만 둬 함께 쓴다(상태 확인은 결과를 보낸 쪽과 상관없다)
        walletResultSender.awaitStatus(refundId, RefundStatus.FAILED);
        assertThat(stepOf(refundId, RefundStepType.ORDER_REVERT).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        assertThat(refundPersistencePort.findById(refundId).map(Refund::getActiveOrderItemId)).isEmpty();
        // 구매자 목록에서 실패와 그 사유를 본다(#649 R3 보완 3, 거절 사유 칸을 같이 씀)
        mockMvc.perform(get(BUYER_URL).header(HttpHeaders.AUTHORIZATION, tokens.bearer(BUYER_ID, "BUYER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].refundId", is(refundId.intValue())))
                .andExpect(jsonPath("$.content[0].status", is("FAILED")))
                .andExpect(jsonPath("$.content[0].rejectReason", is(Refund.FAILED_REASON)));
        requestRefund();
    }

    @Test
    void 재고가_거절돼_돈을_다시_가져온_뒤_주문이_되돌리지_못하면_되돌리기_줄을_멈추고_처리_중으로_남긴다() throws Exception {
        // given: 지갑이 10,000원을 돌려줬는데 상품이 재고 해제를 거절해 회수 요청이 나갔다
        Long refundId = approvedRefundId();
        productStockReleasePort.refuse();
        walletResultSender.sendCompleted(refundId);
        awaitStepStatus(refundId, RefundStepType.WALLET_REVERT, RefundStepStatus.AWAITING_RESULT);
        Map<TopicPartition, Long> before = endOffsets(RefundTopics.REFUND_ORDER_REVERT_REQUESTED);

        // when: 지갑이 다시 가져왔다고 알린다
        walletResultSender.sendReverted(refundId);

        // then: 회수 줄 성공, 이어서 되돌리기 요청이 나갔다
        awaitStepStatus(refundId, RefundStepType.ORDER_REVERT, RefundStepStatus.AWAITING_RESULT);
        assertThat(stepOf(refundId, RefundStepType.WALLET_REVERT).getStatus()).isEqualTo(RefundStepStatus.SUCCEEDED);
        assertThat(readMessagesOf(RefundTopics.REFUND_ORDER_REVERT_REQUESTED, refundId, before)).hasSize(1);

        // when: 주문이 이미 구매확정이라 되돌리지 못했다고 알린다
        orderResultSender.sendRevertFailed(refundId, "ALREADY_CONFIRMED");

        // then: 되돌리기 줄은 그 이유로 멈추고 환불은 처리 중. 멈춘 환불 찾기가 사람에게 알린다
        awaitStepStatus(refundId, RefundStepType.ORDER_REVERT, RefundStepStatus.STOPPED);
        assertThat(stepOf(refundId, RefundStepType.ORDER_REVERT).getLastReason()).isEqualTo("ALREADY_CONFIRMED");
        assertThat(refundPersistencePort.findById(refundId).map(Refund::getStatus)).contains(RefundStatus.PROCESSING);
        assertThat(refundStepPersistencePort.findRefundIdsNeedingAttention(LocalDateTime.now(clock))).contains(refundId);
    }

    private Long approvedRefundId() throws Exception {
        Long refundId = requestRefund();
        mockMvc.perform(patch(SELLER_URL + "/" + refundId)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(SELLER_ID, "BUYER", "SELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision": "APPROVE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")));
        return refundId;
    }

    // 감자 1개 환불을 요청한다. 같은 상품에 진행 중인 요청이 있으면 409라 201이면 진행 중 칸이 비어 있다는 뜻이다
    private Long requestRefund() throws Exception {
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

    private void awaitStepStatus(Long refundId, RefundStepType type, RefundStepStatus expected) {
        RefundWalletResultSender.awaitUntil("환불 " + refundId + "의 " + type + " 줄이 " + expected + "이(가) 되지 않았습니다",
                () -> refundStepPersistencePort.findByRefundIdAndType(refundId, type)
                        .map(step -> step.getStatus() == expected).orElse(false));
    }

    // 그 단계 줄. 없으면 기록이 안 된 것이라 바로 실패한다
    private RefundStep stepOf(Long refundId, RefundStepType type) {
        return refundStepPersistencePort.findByRefundIdAndType(refundId, type).orElseThrow();
    }

    // ---------- 도우미: 토픽 읽기 (RefundWalletRevertIntegrationTest와 같은 방식) ----------

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
        String groupId = "refund-order-revert-test-" + UUID.randomUUID();
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
