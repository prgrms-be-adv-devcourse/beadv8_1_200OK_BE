package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 앱과 같은 접속 환경변수({@code KAFKA_BOOTSTRAP_SERVERS})로 compose가 띄운 실제 Kafka 브로커에 붙는지 검증한다 (#530).
 * 떠 있는 브로커가 필요해 기본 test에서는 빠지고 {@code :common:infraTest}에서만 돈다. single과 cluster 모두 통과한다.
 * 연결만 확인하므로 값 직렬화는 Spring Boot 기본(문자열)을 쓴다. 앱의 JSON 직렬화는 따로 검증한다.
 */
@Tag("infraTest")
@SpringBootTest(classes = KafkaInfraConnectionTest.Config.class,
        properties = "spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:19092,localhost:29092,localhost:39092}")
class KafkaInfraConnectionTest {

    // 앱 전체(DB 등)를 띄우지 않고 Kafka 자동 설정만 켠다. @Configuration을 붙이지 않는다.
    // 다른 모듈의 통합 테스트가 com.ok를 스캔해서 이 설정까지 켜지 않게 한다
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {
    }

    // 브로커가 없을 때 클라이언트 기본값(수십 초)만큼 기다리지 않고 빨리 실패하게 한다
    private static final int TIMEOUT_SECONDS = 10;

    @Autowired
    KafkaTemplate<String, String> sut;

    @Autowired
    KafkaAdmin kafkaAdmin;

    @Autowired
    KafkaProperties properties;

    @Test
    void 브로커에_연결해_클러스터_정보를_조회한다() throws Exception {
        // when
        Collection<Node> nodes;
        String clusterId;
        try (Admin admin = Admin.create(adminProperties())) {
            DescribeClusterResult result = admin.describeCluster();
            nodes = result.nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            clusterId = result.clusterId().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(nodes).isNotEmpty();
            softly.assertThat(clusterId).isNotBlank();
        });
    }

    // KafkaTemplate으로 보내고 일반 컨슈머로 받아, 브로커에 실제로 저장됐는지 본다
    @Test
    void KafkaTemplate으로_발행한_메시지를_컨슈머가_받는다() throws Exception {
        // given
        String topic = "infra-connection-test-" + UUID.randomUUID();
        createTopic(topic);
        try {
            // when
            SendResult<String, String> sent = sut.send(topic, "infra", "infra-ok")
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ConsumerRecord<String, String> received = receiveFirst(topic);

            // then
            assertThat(sent.getRecordMetadata().topic()).isEqualTo(topic);
            assertThat(received).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(received.key()).isEqualTo("infra");
                softly.assertThat(received.value()).isEqualTo("infra-ok");
            });
        } finally {
            deleteTopic(topic);
        }
    }

    private Map<String, Object> adminProperties() {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);
        return config;
    }

    // 복제 수 1이라 single과 cluster 모두에서 만들 수 있다
    private void createTopic(String topic) throws Exception {
        try (Admin admin = Admin.create(adminProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void deleteTopic(String topic) {
        try (Admin admin = Admin.create(adminProperties())) {
            admin.deleteTopics(List.of(topic)).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 정리 실패는 테스트 결과에 영향이 없다
        }
    }

    // 그룹에 가입하지 않고 파티션을 직접 지정해, 그룹 조정을 기다리지 않고 처음부터 읽는다
    private ConsumerRecord<String, String> receiveFirst(String topic) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", properties.getBootstrapServers()),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_SECONDS * 1000);
        TopicPartition partition = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(TIMEOUT_SECONDS).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                if (!records.isEmpty()) {
                    return records.iterator().next();
                }
            }
            return null;
        }
    }

}
