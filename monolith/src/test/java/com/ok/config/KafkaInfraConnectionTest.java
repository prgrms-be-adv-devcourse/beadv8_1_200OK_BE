package com.ok.config;

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
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * 앱 설정으로 compose가 띄운 실제 Kafka 브로커에 붙는지 검증한다 (#530).
 * 떠 있는 브로커가 필요해 기본 test에서는 빠지고 {@code :monolith:infraTest}에서만 돈다. single과 cluster 모두 통과한다.
 */
@Tag("infraTest")
@SpringBootTest(classes = KafkaInfraConnectionTest.Config.class)
class KafkaInfraConnectionTest {

    // 앱 전체(DB 등)를 띄우지 않고 application.yaml로 Kafka 자동 설정만 켠다
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {
    }

    // 브로커가 없을 때 클라이언트 기본값(수십 초)만큼 기다리지 않고 빨리 실패하게 한다
    private static final int TIMEOUT_SECONDS = 10;

    @Autowired
    KafkaTemplate<String, Object> sut;

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

    // 앱의 KafkaTemplate으로 보내고 설정과 무관한 일반 컨슈머로 받아, 브로커에 실제로 저장됐는지 본다
    @Test
    void 앱의_KafkaTemplate으로_발행한_메시지를_컨슈머가_받는다() throws Exception {
        // given
        String topic = "infra-connection-test-" + UUID.randomUUID();
        createTopic(topic);
        try {
            // when
            SendResult<String, Object> sent = sut.send(topic, "infra", Map.of("message", "infra-ok"))
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ConsumerRecord<String, String> received = receiveFirst(topic);

            // then
            assertThat(sent.getRecordMetadata().topic()).isEqualTo(topic);
            assertThat(received).isNotNull();
            assertSoftly(softly -> {
                softly.assertThat(received.key()).isEqualTo("infra");
                softly.assertThat(received.value()).contains("infra-ok");
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
