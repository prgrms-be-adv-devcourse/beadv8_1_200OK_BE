package com.ok.testsupport;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * 테스트가 {@link #TOPIC}으로 보낸 메시지를 받아 모아 두는 컨슈머다 (#538). 보낸 쪽이 실제로 어떤 JSON을 냈는지 확인할 때 쓴다.
 * <p>필요한 테스트가 {@code @Import(TestKafkaConsumer.class)}로만 켠다. {@code @TestComponent}를 붙이지 않는 이유는 {@link AbstractIntegrationTest}가
 * com.ok를 스캔해서 붙이면 모든 통합 테스트에 이 컨슈머가 켜지기 때문이다.
 * 토픽은 테스트가 만들어야 하고(예: {@code NewTopic} 빈),
 * 컨슈머가 파티션을 받기 전에 보낸 메시지는 {@code auto.offset.reset=latest}라 받지 못하므로 {@link #isAssigned()}가 참이 된 뒤에 보낸다.
 */
public class TestKafkaConsumer {

    public static final String TOPIC = "test-topic";

    static final String LISTENER_ID = "testKafkaConsumer";

    private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();

    private final KafkaListenerEndpointRegistry registry;

    public TestKafkaConsumer(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    // 값을 앱 설정의 JSON 변환 없이 문자열로 받는다. 앱이 읽을 타입이 아니라 보낸 JSON 그대로를 확인하려는 것이다
    // 다른 테스트가 같은 그룹의 이전 메시지를 읽지 않도록 latest로 시작한다
    @KafkaListener(
            id = LISTENER_ID,
            topics = TOPIC,
            groupId = "test-group",
            properties = {
                    "auto.offset.reset=latest",
                    "spring.deserializer.value.delegate.class=org.apache.kafka.common.serialization.StringDeserializer"
            })
    public void listen(String message) {
        messageQueue.offer(message);
    }

    /**
     * 컨슈머가 토픽의 파티션을 받았는지 알려 준다. 받은 뒤에 보낸 메시지부터 읽을 수 있다.
     */
    public boolean isAssigned() {
        MessageListenerContainer container = registry.getListenerContainer(LISTENER_ID);
        return container != null && !container.getAssignedPartitions().isEmpty();
    }

    /**
     * 받은 메시지를 하나 꺼낸다. 제한 시간 안에 오지 않으면 {@code null}이다.
     */
    public String getReceivedMessage(long timeout, TimeUnit unit) throws InterruptedException {
        return messageQueue.poll(timeout, unit);
    }

    public void clearMessages() {
        messageQueue.clear();
    }

}
