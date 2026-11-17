package com.ok.refund;

import com.jayway.jsonpath.JsonPath;
import com.ok.refund.adapter.out.event.RefundTopics;
import com.ok.refund.adapter.out.persistence.RefundJpaEntity;
import com.ok.refund.adapter.out.persistence.RefundJpaRepository;
import com.ok.refund.adapter.out.persistence.RefundStepJpaRepository;
import com.ok.refund.application.port.out.RefundPersistencePort;
import com.ok.refund.application.port.out.RefundableOrderItem;
import com.ok.refund.domain.BundleItem;
import com.ok.refund.domain.BundleShipping;
import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundStatus;
import com.ok.refund.application.port.in.RefundResult;
import com.ok.refund.application.port.in.RefundSagaUseCase;
import com.ok.refund.domain.RefundStep;
import com.ok.refund.domain.RefundStepStatus;
import com.ok.refund.domain.RefundStepType;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.FakeOrderCancellationPort;
import com.ok.testsupport.FakeOrderQueryPort;
import com.ok.testsupport.FakeProductStockReleasePort;
import com.ok.testsupport.FakeRefundStepPersistencePort;
import com.ok.testsupport.FakeWalletRefundRequestPort;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 판매자가 승인하면 실제 Kafka(테스트 컨테이너)의 refund.refund-approved.v1 토픽에 지갑 반환 요청이 나가는지 확인한다 (#512)
// 지갑이 .v1로 옮기기 전까지 옛 이름 refund.refund-approved에도 함께 나가는지는 첫 테스트가 본다 (#653)
// - 토픽 메시지는 테스트마다 지워지지 않는다(다른 승인 통합 테스트도 같은 토픽에 보냄). 그래서 승인 직전의 끝 위치부터 읽고,
//   키(orderId)는 여러 환불이 같을 수 있어 값의 refundId로 이번 환불의 메시지를 걸러 본다
// - 값은 문자열로 읽어 JSON 그대로 확인한다. 지갑은 환불 클래스가 아니라 이 JSON 모양으로 읽기 때문이다
// 예시: 구매자 1이 판매자 5의 감자(주문 상품 101) 3개를 주문 30으로 샀다. 10,000원씩, 배송비 3,000원을 냈다
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
    RefundStepJpaRepository refundStepJpaRepository;
    @Autowired
    FakeOrderQueryPort orderQueryPort;
    @Autowired
    FakeOrderCancellationPort orderCancellationPort;
    @Autowired
    FakeProductStockReleasePort productStockReleasePort;
    // 평소에는 실제 토픽으로 보내고, 실패 경로를 볼 때만 발행·단계 줄 저장을 실패시킨다
    @Autowired
    FakeWalletRefundRequestPort walletRefundRequestPort;
    @Autowired
    FakeRefundStepPersistencePort refundStepPersistencePort;
    @Autowired
    RefundPersistencePort refundPersistencePort;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    Clock clock;
    // yaml(spring.kafka)과 테스트 컨테이너 주소로 자동 설정된 컨슈머 팩토리
    @Autowired
    ConsumerFactory<?, ?> consumerFactory;
    @Autowired
    JsonMapper jsonMapper;
    // 알람(#576)이 부를 환불 번호 입구(resume)를 직접 부른다
    @Autowired
    RefundSagaUseCase refundSagaUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        orderQueryPort.put(potato("PREPARING"));
    }

    @AfterEach
    void tearDown() {
        // 단계 줄이 환불 행을 외래 키로 가리켜 먼저 지운다(승인 흐름이 단계 줄을 만든다, #574)
        refundStepJpaRepository.deleteAllInBatch();
        refundJpaRepository.deleteAllInBatch();
        // 통합 테스트는 컨텍스트(Fake 빈)를 함께 쓰므로 비우지 않으면 다른 테스트로 값이 넘어간다
        orderQueryPort.clear();
        orderCancellationPort.clear();
        productStockReleasePort.clear();
        walletRefundRequestPort.clear();
        refundStepPersistencePort.clear();
    }

    @Test
    void 승인하면_지갑과_약속한_JSON으로_반환_요청을_한_건_보낸다() throws Exception {
        // given: 3개 중 1개를 취소한다. 2개(20,000원)가 남아 묶음이 그대로라 배송비는 돌려주지도 빼지도 않는다. 10,000원을 돌려준다
        Long refundId = requestedRefundId();
        Map<TopicPartition, Long> before = endOffsets();
        Map<TopicPartition, Long> beforeOld = endOffsets(RefundTopics.REFUND_APPROVED_OLD);

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")))
                .andExpect(jsonPath("$.refundAmount", is(10000)));

        // then: 지갑이 .v1로 옮기기 전까지 옛 이름 토픽에도 같은 키·같은 JSON이 한 건 나간다 (#653)
        List<ConsumerRecord<?, ?>> sentToOld = readMessagesOf(RefundTopics.REFUND_APPROVED_OLD, refundId, beforeOld);
        assertThat(sentToOld).hasSize(1);
        assertThat(sentToOld.getFirst().key()).isEqualTo(String.valueOf(ORDER_ID));
        String sentToOldValue = (String) sentToOld.getFirst().value();

        // then: 이번 환불의 메시지가 정확히 한 건, 키는 orderId
        List<ConsumerRecord<?, ?>> sent = readMessagesOf(refundId, before);
        assertThat(sent).hasSize(1);
        ConsumerRecord<?, ?> record = sent.getFirst();
        assertThat(record.key()).isEqualTo(String.valueOf(ORDER_ID));
        assertThat(record.value()).isEqualTo(sentToOldValue);
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
        orderQueryPort.put(potato("SHIPPING"));
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));

        // then
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 승인 때는 출고 전이었는데 주문에 알리는 사이 출고됐다. 주문을 지갑보다 먼저 부르므로 돈이 나가기 전에 거절로 끝나야 한다
    @Test
    void 승인_뒤_주문이_반영을_거절하면_REJECTED로_응답하고_반환_요청을_보내지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        orderCancellationPort.markShipped(ORDER_ITEM_ID);
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.rejectReason", is("이미 출고됐거나 취소된 상품이라 취소할 수 없습니다.")));

        // then
        assertThat(readMessagesOf(refundId, before)).isEmpty();
        assertThat(productStockReleasePort.releasedOf(refundId)).isEmpty();
        assertThat(orderCancellationPort.reflectedOf(refundId)).isEmpty();
        // then: 저장된 행도 거절로 끝났다. 거절이면 배송비 정산은 0, 같은 상품의 진행 중 칸은 비어 다시 환불을 요청할 수 있다
        RefundJpaEntity sut = refundJpaRepository.findById(refundId).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(RefundStatus.REJECTED);
            softly.assertThat(sut.getRejectReason()).isNotBlank();
            softly.assertThat(sut.getShippingFeeAmount()).isZero();
            softly.assertThat(sut.getRemoteAreaFeeAmount()).isZero();
            softly.assertThat(sut.getDeductedShippingFeeAmount()).isZero();
            softly.assertThat(sut.getActiveOrderItemId()).isNull();
        });
    }

    // 주문에 알리지 못하면(장애) 지갑보다 먼저 멈춰야 한다. 주문이 모르는 취소의 돈이 나가면 배송될 수도 있는 상품값을 돌려주게 된다
    @Test
    void 승인_뒤_주문_반영이_실패하면_반환_요청을_보내지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        orderCancellationPort.fail();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isServiceUnavailable());

        // then: 환불은 처리 중에 머물고 지갑 메시지·재고 복구는 없다
        assertThat(refundJpaRepository.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(readMessagesOf(refundId, before)).isEmpty();
        assertThat(productStockReleasePort.releasedOf(refundId)).isEmpty();
    }

    // ---------- 승인 뒤 단계의 실패 경로 (#591: 지휘자로 옮기기 #592 전에 지금 동작을 고정한다) ----------

    // 지갑 줄은 보내기 전에 먼저 기록해 커밋한다(#574). 보내기가 실패해도 "지갑: 할 차례"가 남아 다시 시도(#576)가 그 줄을 본다.
    // 판매자는 500을 받는다(브로커 쪽 실패라 표준 예외, RefundWalletKafkaAdapter)
    @Test
    void 승인_뒤_지갑_발행이_실패하면_500이고_지갑_줄이_할_차례로_남는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        walletRefundRequestPort.fail();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isInternalServerError());

        // then
        assertThat(refundJpaRepository.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus, RefundStep::getLastReason)
                .containsExactly(
                        tuple(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, null),
                        tuple(RefundStepType.WALLET, RefundStepStatus.READY, "IllegalStateException"));
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 지갑 발행이 실패해 할 차례로 남은 환불을 같은 refundId로 다시 진행해도 지갑 줄을 새로 저장하지 않고 다시 시작한다(#616 결정 14).
    // 예전에는 다음 단계 줄을 확인 없이 새로 저장해 (refund_id, step) 유일 제약에 걸렸다(11/15 재현)
    @Test
    void 지갑_발행이_실패한_환불을_다시_진행하면_지갑_줄을_다시_시작해_한_번_보낸다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        walletRefundRequestPort.fail();
        approve(refundId)
                .andExpect(status().isInternalServerError());
        walletRefundRequestPort.clear();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        RefundResult result = refundSagaUseCase.resume(refundId);

        // then: 지갑 줄은 같은 줄을 다시 시작해(시도 2번째) 결과 대기가 되고, 반환 요청은 한 건 나간다
        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus, RefundStep::getAttemptCount)
                .containsExactly(
                        tuple(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, 1),
                        tuple(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 2));
        assertThat(readMessagesOf(refundId, before)).hasSize(1);
    }

    // 주문 반영이 실패해 주문 줄이 할 차례로 남은 환불을 다시 진행하면 주문 줄을 다시 시작하고(시도 2번째) 지갑까지 이어 간다.
    // 예전에는 다시 진행해도 주문 줄의 시도 횟수가 오르지 않았다(#616)
    @Test
    void 주문_반영이_실패한_환불을_다시_진행하면_주문부터_다시_해_지갑까지_보낸다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        orderCancellationPort.fail();
        approve(refundId)
                .andExpect(status().isServiceUnavailable());
        orderCancellationPort.clear();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        RefundResult result = refundSagaUseCase.resume(refundId);

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus, RefundStep::getAttemptCount)
                .containsExactly(
                        tuple(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, 2),
                        tuple(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1));
        assertThat(readMessagesOf(refundId, before)).hasSize(1);
    }

    // 지갑 결과를 기다리는 환불은 기다리는 시각(5분) 전이면 다시 진행해도 아무것도 보내지 않는다
    @Test
    void 지갑_결과를_기다리는_환불을_다시_진행하면_아무것도_보내지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        approve(refundId)
                .andExpect(status().isOk());
        Map<TopicPartition, Long> before = endOffsets();

        // when
        RefundResult result = refundSagaUseCase.resume(refundId);

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus, RefundStep::getAttemptCount)
                .containsExactly(
                        tuple(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED, 1),
                        tuple(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT, 1));
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 지갑 결과를 기다리는 시각이 지났는데 결과가 오지 않으면(메시지가 늦거나 버려짐) 같은 refundId로 반환 요청을 한 번 다시 보낸다(#577).
    // 시도 횟수는 그대로이고, 보낸 뒤 다시 결과 대기가 된다
    @Test
    void 지갑_결과를_기다리는_시각이_지난_환불을_다시_진행하면_같은_요청을_한_번_다시_보낸다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        approve(refundId)
                .andExpect(status().isOk());
        makeWalletResultOverdue(refundId);
        Map<TopicPartition, Long> before = endOffsets();

        // when
        RefundResult result = refundSagaUseCase.resume(refundId);

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
        assertThat(wallet).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(wallet.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(wallet.getAttemptCount()).isEqualTo(1);
            softly.assertThat(wallet.getNextCheckAt()).isAfter(LocalDateTime.now(clock));
        });
        assertThat(readMessagesOf(refundId, before)).hasSize(1);
    }

    // 이미 한 번 보낸 요청을 다시 보내다 발행이 실패해도 멈추지 않고 결과 대기로 돌아간다(#577 판단 2).
    // 멈추면 늦게 온 진짜 지갑 답을 기록하지 못한다. 시도 횟수도 그대로다
    @Test
    void 지갑_결과를_다시_보내다_발행이_실패해도_멈추지_않고_결과_대기로_돌아간다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        approve(refundId)
                .andExpect(status().isOk());
        makeWalletResultOverdue(refundId);
        walletRefundRequestPort.fail();

        // when & then
        assertThatThrownBy(() -> refundSagaUseCase.resume(refundId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("지갑 반환 요청 발행에 실패했습니다(테스트). refundId=" + refundId);
        RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
        assertThat(wallet).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(wallet.getStatus()).isEqualTo(RefundStepStatus.AWAITING_RESULT);
            softly.assertThat(wallet.getAttemptCount()).isEqualTo(1);
            softly.assertThat(wallet.getAwaitingSince()).isNotNull();
            softly.assertThat(wallet.getNextCheckAt()).isAfter(LocalDateTime.now(clock));
        });
    }

    // 단계 줄이 없는 처리 중 환불은 있을 수 없는 상태라 다시 진행하지 않는다(#616 결정 13)
    @Test
    void 단계_줄이_없는_처리_중_환불은_다시_진행하지_않는다() throws Exception {
        // given: 승인까지 마친 뒤 단계 줄만 지워 옛 환불 모양으로 만든다
        Long refundId = requestedRefundId();
        approve(refundId)
                .andExpect(status().isOk());
        refundStepJpaRepository.deleteAllInBatch();
        Map<TopicPartition, Long> before = endOffsets();

        // when
        RefundResult result = refundSagaUseCase.resume(refundId);

        // then
        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId)).isEmpty();
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 발행은 이미 끝나 되돌릴 수 없다. 그 뒤 "결과 대기"로 바꾸는 기록이 실패해도 판매자에게는 오류를 주지 않고 처리 중으로 응답한다.
    // 지갑 줄은 "시도 중"으로 남는다(지갑 결과가 오면 시도 중에서도 성공·멈춤으로 바뀐다, #574 결정 2)
    @Test
    void 지갑_발행_뒤_결과_대기_기록이_실패해도_처리_중으로_응답하고_지갑_줄은_시도_중으로_남는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        refundStepPersistencePort.failNextUpdateTo(RefundStepType.WALLET, RefundStepStatus.AWAITING_RESULT);
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROCESSING")));

        // then
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus)
                .containsExactly(
                        tuple(RefundStepType.ORDER, RefundStepStatus.SUCCEEDED),
                        tuple(RefundStepType.WALLET, RefundStepStatus.IN_PROGRESS));
        assertThat(readMessagesOf(refundId, before)).hasSize(1);
    }

    // 주문 반영은 성공했는데 그 결과를 기록하는 구간(주문 성공 + 지갑 줄 만들기)이 실패하면 500이다.
    // 기록이 없으니 지갑에 보내지 않는다(먼저 기록하고 보내기, #574). 주문 줄은 "시도 중"으로 남는다
    @Test
    void 주문_성공_뒤_기록이_실패하면_500이고_지갑에_보내지_않는다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        refundStepPersistencePort.failNextSaveNew(RefundStepType.WALLET);
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isInternalServerError());

        // then
        assertThat(refundJpaRepository.findById(refundId).orElseThrow().getStatus()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(orderCancellationPort.reflectedOf(refundId)).isPresent();
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus)
                .containsExactly(tuple(RefundStepType.ORDER, RefundStepStatus.IN_PROGRESS));
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 주문을 부르는 사이 다른 경로가 환불을 처리 중에서 벗어나게 했으면(여기서는 완료로 바꿔 흉내 낸다) 다음 단계를 부르지 않는다.
    // 끝난 환불에 돈을 보내면 안 되기 때문이다. 응답은 오류 없이 그 순간의 상태다. 주문 성공도 기록하지 않아 주문 줄은 "시도 중"이다
    @Test
    void 주문_반영_사이_환불이_처리_중에서_벗어나면_지갑에_보내지_않고_그_상태로_응답한다() throws Exception {
        // given
        Long refundId = requestedRefundId();
        orderCancellationPort.afterNextReflect(() -> completeRefund(refundId));
        Map<TopicPartition, Long> before = endOffsets();

        // when
        approve(refundId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // then
        assertThat(refundStepPersistencePort.findAllByRefundId(refundId))
                .extracting(RefundStep::getType, RefundStep::getStatus)
                .containsExactly(tuple(RefundStepType.ORDER, RefundStepStatus.IN_PROGRESS));
        assertThat(readMessagesOf(refundId, before)).isEmpty();
    }

    // 지갑 결과 대기 줄의 기다리는 시각을 1분 전으로 옮긴다. 5분을 실제로 기다릴 수 없어 같은 줄을 시각만 바꿔 저장한다(잠근 행에만 update)
    private void makeWalletResultOverdue(Long refundId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            RefundStep wallet = refundStepPersistencePort.findByRefundIdAndType(refundId, RefundStepType.WALLET).orElseThrow();
            refundStepPersistencePort.update(RefundStep.restore(wallet.getId(), refundId, RefundStepType.WALLET,
                    wallet.getStatus(), wallet.getAttemptCount(), LocalDateTime.now(clock).minusMinutes(1),
                    wallet.getLastReason(), wallet.getAwaitingSince(), wallet.getCreatedAt()));
        });
    }

    // 다른 결정처럼 행을 잠그고 바꾼 뒤 저장한다(잠근 행에만 update, refund/CLAUDE.md 4절)
    private void completeRefund(Long refundId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Refund refund = refundPersistencePort.findByIdForUpdate(refundId).orElseThrow();
            refund.complete(LocalDateTime.now(clock));
            refundPersistencePort.update(refund);
        });
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

    private static RefundableOrderItem potato(String status) {
        BundleShipping bundle = new BundleShipping(3_000L, 0, 3_000L, 50_000L,
                List.of(new BundleItem(ORDER_ITEM_ID, 10_000L, 3)));
        return new RefundableOrderItem(ORDER_ID, ORDER_ITEM_ID, BUYER_ID, SELLER_ID, 55L, "감자", "1kg", status,
                10_000L, 3, bundle);
    }

    // ---------- 도우미: 토픽 읽기 (정산 PayoutKafkaPublishIntegrationTest와 같은 방식) ----------

    // from 위치부터 토픽 끝까지 읽어, 값의 refundId가 이번 환불인 메시지만 모은다
    private List<ConsumerRecord<?, ?>> readMessagesOf(Long refundId, Map<TopicPartition, Long> from) throws Exception {
        return readMessagesOf(RefundTopics.REFUND_APPROVED, refundId, from);
    }

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

    private Map<TopicPartition, Long> endOffsets() {
        return endOffsets(RefundTopics.REFUND_APPROVED);
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
        String groupId = "refund-wallet-request-test-" + UUID.randomUUID();
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
