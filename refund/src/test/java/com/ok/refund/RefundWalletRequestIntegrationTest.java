package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.refund.adapter.out.event.RefundTopics;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderQueryPort;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

// 통합 테스트: 판매자가 승인하면 실제 Kafka(테스트 컨테이너)의 refund.refund-approved 토픽에 지갑 반환 요청이 나가는지 확인한다 (#512)
// - 토픽 메시지는 테스트마다 지워지지 않는다(다른 승인 통합 테스트도 같은 토픽에 보냄). 그래서 승인 직전의 끝 위치부터 읽고,
//   키(orderId)는 여러 환불이 같을 수 있어 값의 refundId로 이번 환불의 메시지를 걸러 본다
// - 값은 문자열로 읽어 JSON 그대로 확인한다. 지갑은 환불 클래스가 아니라 이 JSON 모양으로 읽기 때문이다
// 예시: 구매자 1이 판매자 5의 양말(주문 상품 101) 3개를 주문 30으로 샀다. 10,000원씩, 배송비 3,000원을 냈다
class RefundWalletRequestIntegrationTest extends AbstractIntegrationTest {

    private static final String BUYER_URL = "/api/v1/refunds";
    private static final String SELLER_URL = "/api/v1/seller/refunds";
    private static final Long BUYER_ID = 1L;
    private static final Long SELLER_ID = 5L;
    // 다른 환불 통합 테스트(주문 10, 20, 21)와 메시지 키가 겹치지 않게 다른 주문 번호를 쓴다
    private static final Long ORDER_ID = 30L;
    private static final Long ORDER_ITEM_ID = 301L;
    // 토픽을 끝까지 읽는 데 기다리는 최대 시간 (보통 1초 안에 끝남)
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    WebApplicationContext context;
    @Autowired
    TestAccessTokens tokens;
    @Autowired
    RefundJpaRepository refundJpaRepository;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    // yaml(spring.kafka)과 테스트 컨테이너 주소로 자동 설정된 컨슈머 팩토리
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    JsonMapper jsonMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        orderQueryPort.put(socks("PREPARING"));
    }

    @AfterEach
    void tearDown() {
        refundJpaRepository.deleteAllInBatch();
        // 통합 테스트는 컨텍스트(Fake 빈)를 함께 쓰므로 비우지 않으면 다른 테스트로 값이 넘어간다
        orderQueryPort.clear();
    }

    @Test
    void 승인하면_지갑과_약속한_JSON으로_반환_요청을_한_건_보낸다() throws Exception {
        // given: 3개 중 1개를 취소한다. 2개(20,000원)가 남아 묶음이 그대로라 배송비는 돌려주지도 빼지도 않는다. 10,000원을 돌려준다
        Long refundId = requestedRefundId();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.refundAmount", is(10000)));

        // then: 이번 환불의 메시지가 정확히 한 건, 키는 orderId
        List<ConsumerRecord<?, ?>> sent = readMessagesOf(refundId, before);
        assertThat(sent).hasSize(1);
        ConsumerRecord<?, ?> record = sent.getFirst();
        assertThat(record.key()).isEqualTo(String.valueOf(ORDER_ID));
        // then: 값은 #413에 적은 약속 모양. 같은 Long 필드 넷이 서로 다른 값이라 뒤바뀌면 실패한다
        JsonNode message = jsonMapper.readTree((String) record.value());
        assertThat(message).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(message.get("refundId").asLong()).isEqualTo(refundId);
            softly.assertThat(message.get("orderId").asLong()).isEqualTo(ORDER_ID);
            softly.assertThat(message.get("buyerId").asLong()).isEqualTo(BUYER_ID);
            softly.assertThat(message.get("sellerId").asLong()).isEqualTo(SELLER_ID);
            softly.assertThat(message.get("amount").asLong()).isEqualTo(10_000L);
            // 약속한 다섯 필드만 나간다. 필드가 늘면 지갑과 약속을 다시 맞춰야 한다
            softly.assertThat(message.propertyNames()).containsExactlyInAnyOrder(
                    "refundId", "orderId", "buyerId", "sellerId", "amount");
        });
    }

    // 승인 버튼을 눌렀어도 그 사이 출고됐으면 자동 거절로 끝난다. 거절된 환불의 돈이 지갑으로 나가면 안 된다
    @Test
    void 승인_때_출고돼_자동_거절되면_반환_요청을_보내지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        orderQueryPort.put(socks("SHIPPING"));
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        // then
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // ---------- 도우미: 요청·승인 ----------

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

    private static RefundableOrderItem socks(String status) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 3)));
        return new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 55L, "양말", "흰색", status,
                10_000L, 3, bundle);
    }

    // ---------- 도우미: 토픽 읽기 (정산 PayoutKafkaPublishIntegrationTest와 같은 방식) ----------

    // from 위치부터 토픽 끝까지 읽어, 값의 refundId가 이번 환불인 메시지만 모은다
    private List<ConsumerRecord<?, ?>> readMessagesOf(Long refundId, Map<TopicPartition, Long> from) throws Exception {
        try (Consumer<?, ?> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignTopic(consumer);
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

    private Map<TopicPartition, Long> endOffsets() {
        try (Consumer<?, ?> consumer = createConsumer()) {
            return consumer.endOffsets(assignTopic(consumer));
        }
    }

    private Consumer<?, ?> createConsumer() {
        Properties overrides = new Properties();
        // JSON을 클래스로 바꾸지 않고 글자 그대로 읽는다
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 다른 컨슈머 그룹의 읽은 위치와 섞이지 않게 매번 새 그룹으로 읽는다
        String groupId = "refund-wallet-request-test-" + UUID.randomUUID();
        return consumerFactory.createConsumer(groupId, null, null, overrides);
    }

    private List<TopicPartition> assignTopic(Consumer<?, ?> consumer) {
        List<TopicPartition> partitions = new ArrayList<>();
        for (PartitionInfo info : consumer.partitionsFor(RefundTopics.REFUND_APPROVED)) {
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
