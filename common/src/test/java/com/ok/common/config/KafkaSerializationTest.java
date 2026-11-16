package com.ok.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

/**
 * common-kafka.yaml이 정한 직렬화·역직렬화 설정으로 메시지를 보내고 받는 모양을 검증한다 (#538).
 * 브로커 없이, 설정이 가리키는 직렬화 클래스를 그대로 만들어 쓴다. 이 모양이 바뀌면 보내는 쪽과 받는 쪽이 서로 읽지 못한다.
 */
// 설정 값만 확인하므로 앱 전체가 아니라 Kafka 자동 설정만 켠다
@SpringBootTest(classes = KafkaSerializationTest.Config.class, properties = {
        "spring.config.import=classpath:common-kafka.yaml",
        "spring.application.name=kafka-serialization-test"
})
class KafkaSerializationTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(KafkaAutoConfiguration.class)
    static class Config {
    }

    record SampleMessage(Long orderId, long amount, BigDecimal price, LocalDateTime paidAt, String note) {
    }

    private static final String TOPIC = "serialization-test";

    @Autowired
    KafkaProperties properties;

    @Test
    void 공통_설정이_보내는_쪽_직렬화를_정한다() {
        // when
        Map<String, Object> sut = properties.buildProducerProperties();

        // then
        assertSoftly(softly -> {
            softly.assertThat(className(sut.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)))
                    .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
            softly.assertThat(className(sut.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)))
                    .isEqualTo("org.springframework.kafka.support.serializer.JacksonJsonSerializer");
            softly.assertThat(sut.get("spring.json.add.type.headers")).hasToString("false");
            softly.assertThat(sut.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        });
    }

    @Test
    void 공통_설정이_받는_쪽_역직렬화를_정한다() {
        // when
        Map<String, Object> sut = properties.buildConsumerProperties();

        // then
        assertSoftly(softly -> {
            softly.assertThat(className(sut.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)))
                    .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
            softly.assertThat(className(sut.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)))
                    .isEqualTo("org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
            softly.assertThat(className(sut.get("spring.deserializer.value.delegate.class")))
                    .isEqualTo("org.springframework.kafka.support.serializer.JacksonJsonDeserializer");
            softly.assertThat(sut.get("spring.json.use.type.headers")).hasToString("false");
            softly.assertThat(sut.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
            softly.assertThat(sut.get(ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("kafka-serialization-test");
        });
    }

    // 날짜는 ISO-8601 문자열, 금액은 JSON 숫자, null 필드도 이름을 남긴다. 받는 쪽은 이 모양을 보고 읽는다
    @Test
    void 메시지를_클래스_정보_없는_JSON으로_직렬화한다() throws Exception {
        // given
        SampleMessage message = new SampleMessage(7L, 13_000L, new BigDecimal("1500.50"),
                LocalDateTime.of(2026, 10, 30, 14, 0), null);
        RecordHeaders headers = new RecordHeaders();

        // when
        byte[] sut = valueSerializer().serialize(TOPIC, headers, message);

        // then
        assertSoftly(softly -> {
            softly.assertThat(new String(sut, StandardCharsets.UTF_8))
                    .isEqualTo("{\"orderId\":7,\"amount\":13000,\"price\":1500.50,\"paidAt\":\"2026-10-30T14:00:00\",\"note\":null}");
            softly.assertThat(headers.toArray()).isEmpty();
        });
    }

    @Test
    void 보낸_메시지를_받는_쪽_타입으로_다시_읽는다() throws Exception {
        // given
        SampleMessage message = new SampleMessage(7L, 13_000L, new BigDecimal("1500.50"),
                LocalDateTime.of(2026, 10, 30, 14, 0), "메모");
        byte[] sent = valueSerializer().serialize(TOPIC, new RecordHeaders(), message);

        // when
        SampleMessage sut = valueDeserializer().deserialize(TOPIC, new RecordHeaders(), sent);

        // then
        assertThat(sut).isEqualTo(message);
    }

    // 보내는 쪽이 필드를 더해도 받는 쪽이 먼저 배포되지 않았다고 메시지를 못 읽으면 안 된다
    @Test
    void 받는_쪽이_모르는_필드가_있어도_읽는다() throws Exception {
        // given
        byte[] json = "{\"orderId\":7,\"amount\":13000,\"새필드\":\"값\"}".getBytes(StandardCharsets.UTF_8);

        // when
        SampleMessage sut = valueDeserializer().deserialize(TOPIC, new RecordHeaders(), json);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderId()).isEqualTo(7L);
            softly.assertThat(sut.amount()).isEqualTo(13_000L);
        });
    }

    @Test
    void 빠진_필드는_null로_읽는다() throws Exception {
        // given
        byte[] json = "{\"amount\":13000}".getBytes(StandardCharsets.UTF_8);

        // when
        SampleMessage sut = valueDeserializer().deserialize(TOPIC, new RecordHeaders(), json);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.orderId()).isNull();
            softly.assertThat(sut.paidAt()).isNull();
        });
    }

    // 예외를 던지면 컨슈머가 같은 메시지에서 멈춘다. 대신 값은 null, 실패 이유와 원본은 헤더에 남겨 리스너 컨테이너가 오류 처리로 넘기게 한다
    // 원본이 남아 있어야 나중에 DLT가 생겼을 때 메시지를 그대로 보관하고 다시 처리할 수 있다
    // 헤더 이름 상수는 삭제 예고(removal)지만 대체가 공개돼 있지 않다. 삭제되면 이 테스트가 컴파일 단계에서 알려 준다
    @SuppressWarnings("removal")
    @ParameterizedTest
    @MethodSource("읽을_수_없는_메시지")
    void 읽을_수_없는_메시지는_예외를_던지지_않고_원본을_오류_헤더에_남긴다(String payload) throws Exception {
        // given
        RecordHeaders headers = new RecordHeaders();
        byte[] original = payload.getBytes(StandardCharsets.UTF_8);

        // when
        SampleMessage sut = valueDeserializer().deserialize(TOPIC, headers, original);

        // then
        Header errorHeader = headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER);
        assertThat(errorHeader).isNotNull();
        DeserializationException exception = SerializationUtils
                .byteArrayToDeserializationException(new LogAccessor(KafkaSerializationTest.class), errorHeader);
        assertSoftly(softly -> {
            softly.assertThat(sut).isNull();
            softly.assertThat(exception).isNotNull();
            softly.assertThat(exception.getData()).isEqualTo(original);
        });
    }

    static Stream<String> 읽을_수_없는_메시지() {
        return Stream.of("이건 JSON이 아니다", "{\"amount\":\"숫자가 아니다\"}", "[1,2,3]");
    }

    // 설정 값은 클래스 객체나 이름 문자열로 들어 있다
    private static String className(Object value) {
        return value instanceof Class<?> type ? type.getName() : String.valueOf(value);
    }

    // 설정이 가리키는 값 직렬화 클래스를 그대로 만든다
    @SuppressWarnings("unchecked")
    private Serializer<Object> valueSerializer() throws Exception {
        Map<String, Object> config = properties.buildProducerProperties();
        Serializer<Object> serializer = (Serializer<Object>) ((Class<?>) config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
                .getDeclaredConstructor().newInstance();
        serializer.configure(config, false);
        return serializer;
    }

    // 읽을 타입은 리스너마다 정하므로(spring.json.value.default.type) 여기서는 SampleMessage로 정한다
    private Deserializer<SampleMessage> valueDeserializer() {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties());
        config.put(JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, SampleMessage.class.getName());
        ErrorHandlingDeserializer<SampleMessage> deserializer = new ErrorHandlingDeserializer<>();
        deserializer.configure(config, false);
        return deserializer;
    }

}
