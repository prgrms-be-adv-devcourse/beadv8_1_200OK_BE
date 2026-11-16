package com.ok.delivery.adapter.in.event;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.util.backoff.BackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 배송이 Kafka로 받는 메시지의 오류 처리 규칙을 정한다 (#558, #644 결정 ②).
 * 배송 리스너만 이 설정({@link #LISTENER_CONTAINER_FACTORY})을 쓰고, 다른 모듈은 자기 설정이나 Spring Boot 기본 설정을 그대로 쓴다.
 * 출고지 우편번호를 받지 못하면 배송 쪽 사본이 회원과 어긋나고, 결제 완료를 받지 못하면 결제된 주문이 배송되지 않으므로
 * 로그만 남기고 건너뛰지 않고 DLT({@code <토픽>.<그룹>.DLT})에 옮겨 둔다.
 * - {@link IllegalArgumentException}(값이 틀림)과 JSON을 읽지 못한 메시지는 다시 해도 같으므로 바로 DLT로 보낸다.
 * - 그 밖의 예외는 1초부터 두 배씩 3번(1·2·4초) 다시 시도한 뒤 DLT로 보낸다.
 * - DLT로 보내지 못하면 오프셋을 옮기지 않아 같은 메시지를 다시 받는다.
 */
// 모듈 안 위치: Kafka 타입을 쓰는 수신 설정이라 입력 어댑터인 adapter.in.event에 둔다(지갑·환불과 같은 배치)
@Slf4j
@Configuration(proxyBeanMethods = false)
public class DeliveryKafkaConsumerConfig {

    // 배송 @KafkaListener의 containerFactory 속성에 쓰는 빈 이름
    // 기본 이름(kafkaListenerContainerFactory)과 다르게 둬야 Spring Boot 기본 설정이 그대로 만들어져 다른 모듈은 영향을 받지 않는다
    public static final String LISTENER_CONTAINER_FACTORY = "deliveryKafkaListenerContainerFactory";

    // 일시적 실패를 다시 시도하는 횟수와 첫 간격. 1초부터 두 배씩 3번 = 1+2+4 = 약 7초
    // 끝내 실패해도 DLT에 남아 다시 넣을 수 있으므로 오래 붙잡아 뒤 메시지를 기다리게 할 까닭이 적다(지갑과 같음)
    static final int MAX_RETRIES = 3;
    static final long INITIAL_INTERVAL_MILLIS = 1_000L;
    static final double MULTIPLIER = 2.0;

    @Bean(LISTENER_CONTAINER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> deliveryKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ProducerFactory<Object, Object> producerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 역직렬화·그룹 같은 받기 설정은 Spring Boot 설정(common-kafka.yaml)을 그대로 쓰고, 오류 처리기만 바꾼다
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(deliveryErrorHandler(deadLetterRecoverer(kafkaTemplate, producerFactory), deliveryBackOff()));
        return factory;
    }

    // 받는 토픽도 배송이 선언한다(있으면 그대로). 회원이 아직 보내지 않아도 리스너가 "토픽 없음" 경고 없이 붙는다(정산과 같은 방식)
    @Bean
    NewTopic deliverySellerOriginPostalCodeTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.SELLER_ORIGIN_POSTAL_CODE).build();
    }

    @Bean
    NewTopic deliverySellerOriginPostalCodeDltTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.SELLER_ORIGIN_POSTAL_CODE_DLT).build();
    }

    @Bean
    NewTopic deliveryOrderPaidTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.ORDER_PAID).build();
    }

    @Bean
    NewTopic deliveryOrderPaidDltTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.ORDER_PAID_DLT).build();
    }

    @Bean
    NewTopic deliveryOrderProductConfirmedTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.ORDER_PRODUCT_CONFIRMED).build();
    }

    @Bean
    NewTopic deliveryOrderProductConfirmedDltTopic() {
        return TopicBuilder.name(DeliveryInboundTopics.ORDER_PRODUCT_CONFIRMED_DLT).build();
    }

    // 테스트에서 규칙을 Kafka 없이 확인할 수 있게 빈 메서드와 나눠 둔다
    static DefaultErrorHandler deliveryErrorHandler(ConsumerAwareRecordRecoverer recoverer, BackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        // 값이 틀렸다는 예외는 같은 메시지로 다시 해도 결과가 같아 바로 DLT로 보낸다. JSON을 읽지 못한 오류는 기본으로 다시 시도하지 않는다
        // 다른 모듈이 보낸 값의 오류는 표준 예외를 던지기로 해서(exception.md) 입구 전용 예외를 따로 두지 않는다
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    static ExponentialBackOffWithMaxRetries deliveryBackOff() {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MILLIS);
        backOff.setMultiplier(MULTIPLIER);
        return backOff;
    }

    // JSON을 읽지 못한 메시지는 값이 원본 바이트로 돌아오므로 바이트 그대로 보내는 발행기를, 나머지는 부트의 발행기(JSON)를 쓴다.
    // 바이트 발행기를 빈으로 등록하지 않는 이유: KafkaTemplate 빈이 하나 더 생기면 부트가 기본 KafkaTemplate을 만들지 않아 다른 모듈의 주입이 깨진다
    private static ConsumerAwareRecordRecoverer deadLetterRecoverer(KafkaTemplate<Object, Object> kafkaTemplate,
                                                                    ProducerFactory<Object, Object> producerFactory) {
        // 앞에 있는 타입부터 맞춰 보므로 byte[]를 Object보다 먼저 둔다
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, new KafkaTemplate<>(producerFactory,
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        templates.put(Object.class, kafkaTemplate);
        // 그룹이 리스너마다 달라서 실패한 리스너의 그룹을 DLT 이름에 넣는다.
        // 리스너 컨테이너가 컨슈머 스레드를 시작할 때 자기 그룹을 KafkaUtils에 담아 두고, 오류 처리기도 그 스레드에서 돈다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(templates,
                (record, exception) -> new TopicPartition(
                        record.topic() + "." + KafkaUtils.getConsumerGroupId() + ".DLT", -1));
        return (record, consumer, exception) -> {
            // 사람이 DLT를 보고 다시 넣어야 하는 일이라 ERROR로 남긴다. 값에는 배송지(개인정보)가 있어 키와 위치만 남긴다
            log.error("배송이 처리하지 못한 메시지를 DLT로 보냄. topic={}, partition={}, offset={}, key={}",
                    record.topic(), record.partition(), record.offset(), record.key(), exception);
            recoverer.accept(record, consumer, exception);
        };
    }
}
