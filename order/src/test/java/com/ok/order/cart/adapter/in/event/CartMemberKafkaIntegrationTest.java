package com.ok.order.cart.adapter.in.event;

import com.ok.order.OrderSchema;
import com.ok.order.cart.application.service.CartService;
import com.ok.testsupport.AbstractIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 회원가입을 Kafka로 받아 장바구니를 만드는 경로(#601)를 실제 Kafka(Testcontainers)로 확인한다. 테스트가 회원 대신 메시지를 보낸다.
// 메시지는 회원처럼 타입 헤더 없는 JSON 객체로 보낸다(Map은 JSON 객체로 직렬화된다)
class CartMemberKafkaIntegrationTest extends AbstractIntegrationTest {

    // 다른 테스트와 데이터가 겹치지 않게 이 클래스 전용 번호를 쓴다
    private static final Long MEMBER_ID = 9_601_001L;
    // 다시 시도(1·2·4초)를 다 쓰고 기록될 때까지 넉넉히 기다린다
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    CartService cartService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private String missingMemberKey;

    @BeforeEach
    void setUp() {
        missingMemberKey = "missing-" + UUID.randomUUID();
    }

    // 통합 테스트는 롤백되지 않아 이 클래스가 만든 장바구니와 실패 기록을 지운다
    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?",
                missingMemberKey);
        jdbcTemplate.update("DELETE ci FROM " + OrderSchema.NAME + ".cart_item ci JOIN " + OrderSchema.NAME
                + ".cart c ON ci.cart_id = c.id WHERE c.member_id = ?", MEMBER_ID);
        jdbcTemplate.update("DELETE FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?", MEMBER_ID);
    }

    @Test
    void 회원가입을_Kafka로_받으면_장바구니를_만든다() {
        // when
        send(String.valueOf(MEMBER_ID), Map.of("memberId", MEMBER_ID));

        // then
        assertThat(waitFor(() -> cartCount() == 1)).isTrue();
    }

    @Test
    void 같은_회원가입이_두_번_와도_장바구니는_하나다() {
        // given
        send(String.valueOf(MEMBER_ID), Map.of("memberId", MEMBER_ID));

        // when
        RecordMetadata second = send(String.valueOf(MEMBER_ID), Map.of("memberId", MEMBER_ID));

        // then: 두 번째 메시지까지 처리하고 오프셋을 넘겼지만 장바구니는 하나다
        assertThat(waitFor(() -> consumedPast(second))).isTrue();
        assertThat(cartCount()).isEqualTo(1);
    }

    @Test
    void 장바구니를_먼저_담아_만들었어도_회원가입을_받으면_오류_없이_넘어간다() {
        // given: 회원가입 직후 메시지보다 장바구니 담기가 먼저 와서 장바구니가 이미 있다
        cartService.addItem(MEMBER_ID, 9_601_601L, 1);

        // when
        RecordMetadata sent = send(String.valueOf(MEMBER_ID), Map.of("memberId", MEMBER_ID));

        // then
        assertThat(waitFor(() -> consumedPast(sent))).isTrue();
        assertSoftly(softly -> {
            softly.assertThat(cartCount()).isEqualTo(1);
            softly.assertThat(failureCount(String.valueOf(MEMBER_ID))).isZero();
        });
    }

    @Test
    void 회원_ID가_없는_메시지는_다시_시도하지_않고_실패_기록에_남긴다() {
        // when
        send(missingMemberKey, Map.of("name", "회원 ID 없음"));

        // then
        assertThat(waitFor(() -> failureCount(missingMemberKey) == 1)).isTrue();
        Map<String, Object> sut = jdbcTemplate.queryForMap("SELECT consumer_group, topic, failure_reason FROM "
                + OrderSchema.NAME + ".order_message_failure WHERE message_key = ?", missingMemberKey);
        assertSoftly(softly -> {
            softly.assertThat(sut.get("consumer_group")).isEqualTo(CartInboundTopics.CART_CREATE_GROUP);
            softly.assertThat(sut.get("topic")).isEqualTo(CartInboundTopics.MEMBER_REGISTERED);
            softly.assertThat((String) sut.get("failure_reason")).startsWith("IllegalArgumentException");
        });
    }

    private RecordMetadata send(String key, Map<String, Object> message) {
        try {
            return kafkaTemplate.send(CartInboundTopics.MEMBER_REGISTERED, key, message).get().getRecordMetadata();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 메시지를 보내지 못했습니다. key=" + key, e);
        }
    }

    // 장바구니 그룹이 이 메시지 다음 위치까지 오프셋을 커밋했는지. 커밋은 리스너 처리가 끝난 뒤에 한다
    private boolean consumedPast(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(CartInboundTopics.CART_CREATE_GROUP)
                    .partitionsToOffsetAndMetadata().get().get(partition);
            return committed != null && committed.offset() > sent.offset();
        } catch (Exception e) {
            return false;
        }
    }

    private int cartCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME + ".cart WHERE member_id = ?",
                Integer.class, MEMBER_ID);
    }

    private int failureCount(String key) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + OrderSchema.NAME
                + ".order_message_failure WHERE message_key = ?", Integer.class, key);
    }

    private boolean waitFor(Supplier<Boolean> condition) {
        Instant until = Instant.now().plus(WAIT_TIMEOUT);
        while (Instant.now().isBefore(until)) {
            if (condition.get()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
