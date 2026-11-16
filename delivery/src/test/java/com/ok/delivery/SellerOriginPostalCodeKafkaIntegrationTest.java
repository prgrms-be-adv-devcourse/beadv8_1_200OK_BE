package com.ok.delivery;

import com.ok.delivery.adapter.in.event.DeliveryInboundTopics;
import com.ok.delivery.adapter.out.persistence.SellerOriginPostalCodeJpaRepository;
import com.ok.delivery.domain.SellerOriginPostalCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;

// 통합 테스트: member가 Kafka로 보낸 출고지 우편번호를 배송 리스너가 받아 테이블에 저장하는지, 값이 틀린 메시지를 DLT로 옮기는지 확인한다.
// - member 코드는 쓰지 않고, member가 보내기로 한 JSON(sellerId, postalCode, occurredAt)을 테스트가 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 리포지토리로 확인한다
// - 테스트마다 겹치지 않는 판매자 번호를 쓰고, 정리할 때도 그 판매자의 행만 지운다
class SellerOriginPostalCodeKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // 이 클래스 전용 판매자 번호대
    private static final AtomicLong SELLER_ID_SEQ = new AtomicLong(9_100_000L);

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private SellerOriginPostalCodeJpaRepository repository;

    @Autowired
    private KafkaConnectionDetails kafkaConnectionDetails;

    private final List<Long> usedSellerIds = new ArrayList<>();

    private DeliveryTopicReader dltReader;

    @BeforeEach
    void setUp() {
        dltReader = new DeliveryTopicReader(kafkaConnectionDetails.getBootstrapServers(),
                DeliveryInboundTopics.SELLER_ORIGIN_POSTAL_CODE_DLT);
    }

    @AfterEach
    void tearDown() {
        dltReader.close();
        repository.deleteAllById(usedSellerIds);
        usedSellerIds.clear();
    }

    @Test
    void 출고지_우편번호를_받으면_판매자별로_저장한다() {
        // given
        long sellerId = nextSellerId();

        // when
        send(sellerId, "63100", "2026-11-13T10:00:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(sellerId)).isPresent());
        SellerOriginPostalCode sut = repository.findById(sellerId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo("63100");
            softly.assertThat(sut.getSourceOccurredAt().toString()).isEqualTo("2026-11-13T10:00");
        });
    }

    @Test
    void 같은_판매자의_새_메시지는_값을_덮어쓰고_오래된_메시지는_버린다() {
        // given
        long sellerId = nextSellerId();
        send(sellerId, "63100", "2026-11-13T10:00:00");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(sellerId)).isPresent());

        // when: 더 새로운 값, 그리고 순서가 뒤바뀌어 늦게 온 옛 값을 차례로 보낸다
        send(sellerId, "06236", "2026-11-13T11:00:00");
        send(sellerId, "48058", "2026-11-13T09:00:00");

        // then: 뒤에 보낸 판매자가 처리될 때까지 기다리면 앞선 두 메시지도 처리된 것이다(같은 키라 한 파티션으로 가서 순서대로 처리된다)
        long laterSellerId = nextSellerId();
        send(laterSellerId, "12345", "2026-11-13T10:00:00");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(laterSellerId)).isPresent());
        assertThat(repository.findById(sellerId).orElseThrow().getPostalCode()).isEqualTo("06236");
    }

    @Test
    void member가_보내는_마이크로초_시각을_그대로_저장하고_같은_메시지가_다시_와도_결과가_같다() {
        // given: member는 occurredAt을 마이크로초까지 보낸다(#587 메시지 약속)
        long sellerId = nextSellerId();
        send(sellerId, "37421", "2026-11-15T10:00:00.123456");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(sellerId)).isPresent());

        // when: 최소 한 번 전달이라 같은 메시지가 한 번 더 온다
        send(sellerId, "37421", "2026-11-15T10:00:00.123456");
        long laterSellerId = nextSellerId();
        send(laterSellerId, "12345", "2026-11-15T10:00:00");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(laterSellerId)).isPresent());

        // then
        SellerOriginPostalCode sut = repository.findById(sellerId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getPostalCode()).isEqualTo("37421");
            softly.assertThat(sut.getSourceOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 10, 0, 0, 123_456_000));
        });
    }

    @Test
    void 우편번호가_빈_메시지는_출고지를_비운다() {
        // given
        long sellerId = nextSellerId();
        send(sellerId, "63100", "2026-11-13T10:00:00");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(sellerId)).isPresent());

        // when
        send(sellerId, null, "2026-11-13T11:00:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(repository.findById(sellerId).orElseThrow().getPostalCode()).isNull());
    }

    @Test
    void 값이_틀린_메시지는_저장하지_않고_DLT로_옮긴_뒤_다음_메시지를_처리한다() {
        // given: 우편번호가 숫자 5자리가 아닌 메시지
        long invalidSellerId = nextSellerId();
        long laterSellerId = nextSellerId();

        // when
        send(invalidSellerId, "abc", "2026-11-13T10:00:00");
        send(laterSellerId, "63100", "2026-11-13T10:00:00");

        // then: 건너뛰지 않고 DLT에 남겨야 사람이 원인을 보고 고쳐 다시 넣을 수 있다
        assertThat(dltReader.awaitValuesFor(String.valueOf(invalidSellerId), TIMEOUT))
                .singleElement().asString().contains("\"postalCode\":\"abc\"");
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(repository.findById(laterSellerId)).isPresent());
        assertThat(repository.findById(invalidSellerId)).isEmpty();
    }

    private long nextSellerId() {
        long sellerId = SELLER_ID_SEQ.incrementAndGet();
        usedSellerIds.add(sellerId);
        return sellerId;
    }

    // 키는 member와 합의한 대로 sellerId 문자열이다
    private void send(long sellerId, String postalCode, String occurredAt) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("sellerId", sellerId);
        message.put("postalCode", postalCode);
        message.put("occurredAt", occurredAt);
        kafkaTemplate.send(DeliveryInboundTopics.SELLER_ORIGIN_POSTAL_CODE, String.valueOf(sellerId), message);
    }
}
