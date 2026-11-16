package com.ok.payout;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.adapter.in.batch.PayoutOutboxResendScheduler;
import com.ok.payout.adapter.out.persistence.PayoutDepositCheckJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutOutboxJpaRepository;
import com.ok.payout.adapter.out.persistence.PayoutWalletDeductionJpaRepository;
import com.ok.payout.application.port.in.PayoutDepositCheckRunResult;
import com.ok.payout.application.port.in.PayoutDepositCheckUseCase;
import com.ok.payout.application.service.PayoutDepositCheckRecordService;
import com.ok.payout.config.PayoutTopics;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutDepositCheck;
import com.ok.payout.domain.PayoutDepositCheckStatus;
import com.ok.payout.domain.PayoutDepositMismatchReason;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.domain.PayoutOutbox;
import com.ok.payout.domain.PayoutOutboxStatus;
import com.ok.payout.domain.PayoutWalletDeduction;
import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.testsupport.TestAccessTokens;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 통합 테스트: 정산·지갑 대사 (#619)와 관리자 확인 목록 API (#621)
// - 정산 Job으로 정산을 실제로 확정해 대사 줄을 만들고, 대사를 돌려 질문이 실제 Kafka 토픽으로 나가는지 본다
// - 지갑 코드는 쓰지 않고, 지갑이 보낼 답 JSON(PR #548 문서 4-3절, 지갑 #613)을 테스트가 답 토픽에 직접 넣는다
// - 리스너는 별도 스레드에서 받으므로 결과는 await()로 기다린 뒤 리포지토리로 확인한다
// - 정산·스냅샷은 조회 하한 기준이 되므로 다른 정산 통합 테스트처럼 테스트 전후로 전부 비운다 (PayoutKafkaPublishIntegrationTest와 같은 방식)
class PayoutDepositCheckIntegrationTest extends AbstractIntegrationTest {

    // 이 클래스 전용 주문 번호대 (다른 정산 통합 테스트가 쓰지 않는 범위). 주문항목 ID에도 같은 번호를 씀
    private static final long ORDER_ID_START = 20_000_000L;
    private static final long ORDER_ID_END = 21_000_000L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(ORDER_ID_START);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final long ADMIN_ID = 1L;

    @Autowired
    private PayoutJobRunner payoutJobRunner;

    @Autowired
    private PayoutDepositCheckUseCase payoutDepositCheckUseCase;

    // 닫기가 실패한 날의 묻기 대상 조회만 따로 확인하려고 씀 (닫기 실패를 통합 테스트로 일으키기 어려워서)
    @Autowired
    private PayoutDepositCheckRecordService depositCheckRecordService;

    @Autowired
    private PayoutDepositCheckJpaRepository depositCheckRepository;

    @Autowired
    private PayoutJpaRepository payoutRepository;

    @Autowired
    private PayoutOutboxJpaRepository outboxRepository;

    @Autowired
    private PayoutOutboxResendScheduler payoutOutboxResendScheduler;

    @Autowired
    private PayoutWalletDeductionJpaRepository payoutWalletDeductionRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private ConsumerFactory<?, ?> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TestAccessTokens tokens;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cleanUp();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    void 정산을_확정하면_지갑에_입금_메시지를_보내는_정산만_대사_줄이_생긴다() throws Exception {
        // given: 판매자 10은 9,700원(수수료 300원은 시스템 몫), 판매자 20은 5,000원에 -5,000원 조정이 붙어 0원
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        long orderA = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderA, 10L, 9_700L, createdAt);
        insertCandidate(PayoutEventType.SALE_FEE, orderA, 10L, 300L, createdAt);
        long orderB = nextOrderId();
        insertCandidate(PayoutEventType.SALE_AMOUNT, orderB, 20L, 5_000L, createdAt);
        insertAdjustment(orderB, 20L, -5_000L, createdAt);

        // when
        JobExecution execution = payoutJobRunner.runScheduled();

        // then: 판매자 10의 정산만 확인 대기 줄 1건 (0원·시스템 몫은 지갑에 안 보내 대사도 안 함)
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutDepositCheck> checks = depositCheckRepository.findAll();
        assertThat(checks).hasSize(1);
        PayoutDepositCheck sut = checks.get(0);
        Payout seller10 = payoutRepository.findById(sut.getPayoutId()).orElseThrow();
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(seller10.getPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getAmount()).isEqualTo(9_700L);
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
            softly.assertThat(sut.getAttemptCount()).isZero();
        });
    }

    @Test
    void 대사를_돌리면_대기_줄을_묶어_약속한_모양으로_지갑에_묻는다() throws Exception {
        // given: 확인 대기 정산 1건
        Long payoutId = settleOnePayout(10L, 9_700L);

        // when
        PayoutDepositCheckRunResult result = payoutDepositCheckUseCase.runCheck();

        // then: 묶음 1개에 1건을 물었고, 줄에 물은 횟수와 묶음 번호가 남음
        assertThat(result.askedCount()).isEqualTo(1);
        PayoutDepositCheck check = checkOf(payoutId);
        assertThat(check.getAttemptCount()).isEqualTo(1);
        Long checkId = check.getLastCheckId();
        assertThat(checkId).isNotNull();

        // then: 질문 토픽에 묶음 번호(키)로 지갑과 약속한 JSON이 나감
        String sent = readMessage(PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED, String.valueOf(checkId));
        assertThat(sent).isNotNull();
        // 지갑이 .v1로 옮기기 전까지 옛 이름 토픽에도 같은 JSON이 나감 (#653)
        assertThat(readMessage(PayoutTopics.PAYOUT_DEPOSIT_CHECK_REQUESTED_OLD, String.valueOf(checkId))).isEqualTo(sent);
        JsonNode message = jsonMapper.readTree(sent);
        JsonNode item = message.get("items").get(0);
        assertSoftly(softly -> {
            softly.assertThat(message.get("checkId").asLong()).isEqualTo(checkId);
            softly.assertThat(message.get("items").size()).isEqualTo(1);
            softly.assertThat(item.get("payoutId").asLong()).isEqualTo(payoutId);
            softly.assertThat(item.get("payeeId").asLong()).isEqualTo(10L);
            softly.assertThat(item.get("amount").asLong()).isEqualTo(9_700L);
        });
    }

    @Test
    void 지갑이_같은_판매자_금액으로_있다고_답하면_일치로_끝난다() throws Exception {
        // given: 정산 1건을 물음
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when: 지갑 답 (depositedAt은 오프셋 없는 ISO-8601)
        sendAnswer(checkId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        PayoutDepositCheck sut = checkOf(payoutId);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getWalletPayeeId()).isEqualTo(10L);
            softly.assertThat(sut.getWalletAmount()).isEqualTo(9_700L);
            softly.assertThat(sut.getWalletDepositedAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 2, 5));
        });
    }

    @Test
    void 지갑이_금액이_다르다고_답하면_바로_관리자_확인이다() throws Exception {
        // given
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when
        sendAnswer(checkId, payoutId, true, 10L, 9_000L, "2026-11-15T02:05:00");

        // then
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW));
        assertThat(checkOf(payoutId).getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.AMOUNT_MISMATCH);
    }

    @Test
    void 지갑이_없다고_답하면_outbox_줄을_되돌려_재발송이_다시_보내고_같은_답이_두_번_와도_한_번만_센다() throws Exception {
        // given: 판매자 10·20의 정산 2건을 한 묶음으로 물음
        Map<Long, Long> payoutIdBySeller = settlePayouts(Map.of(10L, 9_700L, 20L, 5_000L));
        Long payoutA = payoutIdBySeller.get(10L);
        Long payoutB = payoutIdBySeller.get(20L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutA).getLastCheckId();
        Map<TopicPartition, Long> before = endOffsets(PayoutTopics.SELLER_PAYOUT_CREATED);

        // when: 정산 A의 같은 "없음" 답이 두 번 온 뒤, 표시용으로 정산 B의 "일치" 답이 옴
        sendAnswer(checkId, payoutA, false, null, null, null);
        sendAnswer(checkId, payoutA, false, null, null, null);
        sendAnswer(checkId, payoutB, true, 20L, 5_000L, "2026-11-15T02:05:00");

        // then: B가 처리될 때까지 기다린 뒤 A를 확인함
        // 같은 키(checkId)라 같은 파티션에서 보낸 순서대로 처리되므로, B가 처리됐으면 A의 두 번째 답도 이미 처리된 것이다
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutB).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        PayoutDepositCheck sut = checkOf(payoutA);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getNotFoundCount()).isEqualTo(1);
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
        });

        // then: 답 처리는 A의 outbox 줄만 다시 보낼 것(PENDING)으로 되돌리고 보낸 시각을 비움 (#620). 일치한 B는 보냄 그대로
        PayoutOutbox outboxA = outboxOf(payoutA);
        assertThat(outboxA).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(outboxA.getStatus()).isEqualTo(PayoutOutboxStatus.PENDING);
            softly.assertThat(outboxA.getSentAt()).isNull();
            softly.assertThat(outboxOf(payoutB).getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
        });

        // when: 짧은 간격 재발송이 돎 (실제로는 5분마다, 여기서는 직접 부름)
        payoutOutboxResendScheduler.resendPending();

        // then: A의 입금 메시지(같은 payoutId)가 다시 나가고 줄은 보냄이 됨
        assertThat(outboxOf(payoutA).getStatus()).isEqualTo(PayoutOutboxStatus.SENT);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(readMessage(PayoutTopics.SELLER_PAYOUT_CREATED, String.valueOf(payoutA), before)).isNotNull());
    }

    @Test
    void 이전_질문의_늦은_없음은_버린다() throws Exception {
        // given: 판매자 10·20의 정산을 어제(첫 번째 대사)와 오늘(두 번째 대사) 한 묶음씩 물음
        Map<Long, Long> payoutIdBySeller = settlePayouts(Map.of(10L, 9_700L, 20L, 5_000L));
        Long payoutA = payoutIdBySeller.get(10L);
        Long payoutB = payoutIdBySeller.get(20L);
        payoutDepositCheckUseCase.runCheck();
        Long yesterdayCheckId = checkOf(payoutA).getLastCheckId();
        payoutDepositCheckUseCase.runCheck();
        assertThat(checkOf(payoutA).getLastCheckId()).isNotEqualTo(yesterdayCheckId);

        // when: 어제 질문으로 정산 A의 "없음"이 늦게 온 뒤, 표시용으로 같은 어제 질문의 정산 B "있음, 같은 값"이 옴
        sendAnswer(yesterdayCheckId, payoutA, false, null, null, null);
        sendAnswer(yesterdayCheckId, payoutB, true, 20L, 5_000L, "2026-11-15T02:05:00");

        // then: B가 처리될 때까지 기다린 뒤 A를 확인함
        // 같은 키(어제 checkId)라 같은 파티션에서 보낸 순서대로 처리되므로, B가 처리됐으면 A의 늦은 "없음"도 이미 처리된 것이다
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutB).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        PayoutDepositCheck sut = checkOf(payoutA);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getNotFoundCount()).isZero();
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
        });
    }

    @Test
    void 지갑_답_없음으로_닫힌_뒤_늦은_있음이_같은_값이면_일치로_바뀐다() throws Exception {
        // given: 세 번 물었는데 답이 없어 네 번째 대사에서 지갑 답 없음으로 닫힘
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long firstCheckId = checkOf(payoutId).getLastCheckId();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        assertThat(checkOf(payoutId).getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NO_RESPONSE);
        Map<TopicPartition, Long> before = endOffsets(PayoutTopics.SELLER_PAYOUT_CREATED);
        Map<TopicPartition, Long> beforeOld = endOffsets(PayoutTopics.SELLER_PAYOUT_CREATED_OLD);

        // when: 첫 질문의 "있음, 같은 값"이 이제야 옴
        sendAnswer(firstCheckId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then: 일치로 바뀌고, 입금 메시지는 .v1과 옛 이름 어느 쪽으로도 다시 나가지 않음 (#653)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        assertThat(checkOf(payoutId).getMismatchReason()).isNull();
        assertThat(readMessage(PayoutTopics.SELLER_PAYOUT_CREATED, String.valueOf(payoutId), before)).isNull();
        assertThat(readMessage(PayoutTopics.SELLER_PAYOUT_CREATED_OLD, String.valueOf(payoutId), beforeOld)).isNull();
    }

    @Test
    void 이전_질문의_늦은_있음은_같은_값이면_일치로_끝낸다() throws Exception {
        // given: 어제 질문과 오늘 질문. 오늘 질문의 답은 오지 않음
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long yesterdayCheckId = checkOf(payoutId).getLastCheckId();
        payoutDepositCheckUseCase.runCheck();

        // when: 어제 질문의 "있음, 같은 값"이 늦게 옴
        sendAnswer(yesterdayCheckId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then: 지갑 원장 입금은 지워지지 않아 옛날 "있음"도 사실이라 일치로 끝냄
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
    }

    @Test
    void 세_번_물어도_답이_한_번도_없으면_다음_대사에서_지갑_답_없음으로_닫는다() throws Exception {
        // given: 세 번 물었는데 지갑이 한 번도 답하지 않음
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        assertThat(checkOf(payoutId).getAttemptCount()).isEqualTo(PayoutDepositCheck.MAX_ATTEMPTS);

        // when: 네 번째 대사
        PayoutDepositCheckRunResult result = payoutDepositCheckUseCase.runCheck();

        // then: 묻지 않고 닫음
        assertThat(result.closedCount()).isEqualTo(1);
        assertThat(result.askedCount()).isZero();
        PayoutDepositCheck sut = checkOf(payoutId);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW);
            softly.assertThat(sut.getMismatchReason()).isEqualTo(PayoutDepositMismatchReason.NO_RESPONSE);
            softly.assertThat(sut.getAttemptCount()).isEqualTo(PayoutDepositCheck.MAX_ATTEMPTS);
        });
    }

    @Test
    void 세_번_물은_대기_줄은_닫지_못한_날에도_다시_묻지_않는다() throws Exception {
        // given: 세 번 물어 대기로 남은 줄 (닫기가 실패한 날과 같은 상태)
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        Long thirdCheckId = checkOf(payoutId).getLastCheckId();

        // when: 묻기 대상 묶음을 만듦
        Optional<?> next = depositCheckRecordService.prepareNextBatch(0L);

        // then: 4번째 질문에 담지 않음 (담았다면 recordAsked가 예외를 던져 묻기가 멈춤)
        assertThat(next).isEmpty();
        assertSoftly(softly -> {
            softly.assertThat(checkOf(payoutId).getAttemptCount()).isEqualTo(PayoutDepositCheck.MAX_ATTEMPTS);
            softly.assertThat(checkOf(payoutId).getLastCheckId()).isEqualTo(thirdCheckId);
        });
    }

    @Test
    void 묶음_번호가_없는_답은_버리고_다음_답을_처리한다() throws Exception {
        // given
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when: 묶음 번호가 빠진 답, 이어서 정상 답
        Map<String, Object> broken = Map.of("items", List.of(Map.of("payoutId", payoutId, "found", false)));
        kafkaTemplate.send(PayoutTopics.WALLET_PAYOUT_DEPOSIT_CHECKED, "broken", broken);
        sendAnswer(checkId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then: 틀린 답은 다시 시도하지 않고 넘어가 정상 답이 반영됨 (틀린 답이 반영됐다면 없음 1번이 남음)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        assertThat(checkOf(payoutId).getNotFoundCount()).isZero();
    }

    @Test
    void 답_묶음이_500건을_넘으면_반영하지_않고_다음_답을_처리한다() throws Exception {
        // given
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();

        // when: 이 정산의 "없음"을 포함한 501건짜리 답, 이어서 같은 키로 정상 "있음, 같은 값" 답
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(answerItem(payoutId, false, null, null, null));
        for (long i = 1; i <= 500; i++) {
            items.add(answerItem(900_000_000L + i, false, null, null, null));
        }
        kafkaTemplate.send(PayoutTopics.WALLET_PAYOUT_DEPOSIT_CHECKED, String.valueOf(checkId), Map.of("checkId", checkId, "items", items));
        sendAnswer(checkId, payoutId, true, 10L, 9_700L, "2026-11-15T02:05:00");

        // then: 큰 묶음은 다시 시도 없이 넘어가고 정상 답이 반영됨 (큰 묶음이 반영됐다면 없음 1번이 남음)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        assertThat(checkOf(payoutId).getNotFoundCount()).isZero();
    }

    @Test
    void 있음인데_판매자_금액이_빈_항목은_건너뛰고_대기로_남긴다() throws Exception {
        // given: 판매자 10·20의 정산을 한 묶음으로 물음
        Map<Long, Long> payoutIdBySeller = settlePayouts(Map.of(10L, 9_700L, 20L, 5_000L));
        Long payoutA = payoutIdBySeller.get(10L);
        Long payoutB = payoutIdBySeller.get(20L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutA).getLastCheckId();

        // when: 정산 A는 "있음"인데 판매자·금액이 빔(지갑 버그), 표시용으로 같은 키의 정산 B "있음, 같은 값"
        sendAnswer(checkId, payoutA, true, null, null, "2026-11-15T02:05:00");
        sendAnswer(checkId, payoutB, true, 20L, 5_000L, "2026-11-15T02:05:00");

        // then: B가 처리된 뒤 A는 판매자 다름으로 기록되지 않고 대기로 남음 (다음 날 다시 물음)
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutB).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED));
        PayoutDepositCheck sut = checkOf(payoutA);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);
            softly.assertThat(sut.getMismatchReason()).isNull();
        });
    }

    // ---------- 관리자 확인 목록 API (#621) ----------

    @Test
    void 관리자_목록은_판매자_다름_정산을_지갑이_답한_판매자_금액과_함께_보여_준다() throws Exception {
        // given: 판매자 10의 9,700원 정산을 물었는데 지갑이 판매자 99에게 9,600원 입금이 있다고 답함
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(payoutId).getLastCheckId();
        sendAnswer(checkId, payoutId, true, 99L, 9_600L, "2026-11-15T02:05:00");
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW));

        // when
        String body = mockMvc.perform(depositChecksRequest().header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andReturn().getResponse().getContentAsString();

        // then: 정산이 기대한 값과 지갑이 답한 값을 나란히 보여 줌. 물은 시각은 대사 줄의 마지막으로 물은 시각
        JsonNode sut = jsonMapper.readTree(body).get("content").get(0);
        assertThat(sut).isNotNull();
        LocalDateTime askedAt = checkOf(payoutId).getLastCheckedAt();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("payoutId").asLong()).isEqualTo(payoutId);
            softly.assertThat(sut.get("payeeId").asLong()).isEqualTo(10L);
            softly.assertThat(sut.get("amount").asLong()).isEqualTo(9_700L);
            softly.assertThat(sut.get("walletPayeeId").asLong()).isEqualTo(99L);
            softly.assertThat(sut.get("walletAmount").asLong()).isEqualTo(9_600L);
            softly.assertThat(sut.get("reason").asString()).isEqualTo("PAYEE_MISMATCH");
            softly.assertThat(sut.get("attemptCount").asInt()).isEqualTo(1);
            softly.assertThat(sut.get("notFoundCount").asInt()).isZero();
            softly.assertThat(LocalDateTime.parse(sut.get("lastAskedAt").asString())).isEqualTo(askedAt);
        });
    }

    @Test
    void 관리자_목록은_지갑_답_없음_정산의_지갑_값을_null로_보여_준다() throws Exception {
        // given: 세 번 물었는데 답이 없어 네 번째 대사에서 지갑 답 없음으로 닫힘
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();

        // when & then: "있음" 답을 받지 못해 지갑 값이 없음
        mockMvc.perform(depositChecksRequest().header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payoutId").value(payoutId))
                .andExpect(jsonPath("$.content[0].walletPayeeId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].walletAmount").value(nullValue()))
                .andExpect(jsonPath("$.content[0].reason").value("NO_RESPONSE"))
                .andExpect(jsonPath("$.content[0].attemptCount").value(PayoutDepositCheck.MAX_ATTEMPTS))
                .andExpect(jsonPath("$.content[0].notFoundCount").value(0));
    }

    @Test
    void 관리자_목록은_입금_없음_정산의_없음_답_횟수를_보여_준다() throws Exception {
        // given: 첫 질문에 지갑이 "없음"으로 답하고, 두·세 번째 질문에는 답이 없어 네 번째 대사에서 입금 없음으로 닫힘
        //   이유만 보면 3번 다 "없음"인지 알 수 없어, 없음 답 횟수(1)와 시도 횟수(3)를 함께 보여 줌 (#621 사용자 결정)
        Long payoutId = settleOnePayout(10L, 9_700L);
        payoutDepositCheckUseCase.runCheck();
        Long firstCheckId = checkOf(payoutId).getLastCheckId();
        sendAnswer(firstCheckId, payoutId, false, null, null, null);
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(checkOf(payoutId).getNotFoundCount()).isEqualTo(1));
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();

        // when & then
        mockMvc.perform(depositChecksRequest().header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payoutId").value(payoutId))
                .andExpect(jsonPath("$.content[0].reason").value("NOT_FOUND"))
                .andExpect(jsonPath("$.content[0].attemptCount").value(PayoutDepositCheck.MAX_ATTEMPTS))
                .andExpect(jsonPath("$.content[0].notFoundCount").value(1));
    }

    @Test
    void 관리자_목록에는_확인_대기와_일치_정산이_나오지_않는다() throws Exception {
        // given: 판매자 10은 금액 다름(관리자 확인), 20은 일치, 30은 답이 오지 않아 확인 대기
        Map<Long, Long> payoutIdBySeller = settlePayouts(Map.of(10L, 9_700L, 20L, 5_000L, 30L, 3_000L));
        Long review = payoutIdBySeller.get(10L);
        Long matched = payoutIdBySeller.get(20L);
        Long waiting = payoutIdBySeller.get(30L);
        payoutDepositCheckUseCase.runCheck();
        Long checkId = checkOf(review).getLastCheckId();
        sendAnswer(checkId, review, true, 10L, 9_000L, "2026-11-15T02:05:00");
        sendAnswer(checkId, matched, true, 20L, 5_000L, "2026-11-15T02:05:00");
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(checkOf(review).getStatus()).isEqualTo(PayoutDepositCheckStatus.NEEDS_REVIEW);
            assertThat(checkOf(matched).getStatus()).isEqualTo(PayoutDepositCheckStatus.MATCHED);
        });
        assertThat(checkOf(waiting).getStatus()).isEqualTo(PayoutDepositCheckStatus.WAITING);

        // when & then: 관리자 확인 필요 1건만 (개수에서도 다른 상태는 빠짐)
        //   크기 1로 부름: 첫 쪽이 크기보다 적게 차면 PageableExecutionUtils가 개수 쿼리를 건너뛰어 개수 쿼리의 상태 조건을 확인하지 못함
        mockMvc.perform(depositChecksRequest().param("size", "1").header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(review.intValue())))
                .andExpect(jsonPath("$.content[0].reason").value("AMOUNT_MISMATCH"))
                .andExpect(jsonPath("$.content[0].walletAmount").value(9_000L));
    }

    @Test
    void 관리자_목록은_대사_줄_id_내림차순이고_페이지로_나눈다() throws Exception {
        // given: 정산 3건이 모두 지갑 답 없음으로 닫힘
        settlePayouts(Map.of(10L, 9_700L, 20L, 5_000L, 30L, 3_000L));
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        payoutDepositCheckUseCase.runCheck();
        List<Integer> payoutIdsInCheckOrder = new ArrayList<>();
        for (PayoutDepositCheck check : depositCheckRepository.findAll(Sort.by("id"))) {
            payoutIdsInCheckOrder.add(check.getPayoutId().intValue());
        }
        String admin = tokens.bearer(ADMIN_ID, "ADMIN");

        // when & then: 새 대사 줄 먼저, 크기 2면 2쪽
        mockMvc.perform(depositChecksRequest().param("page", "0").param("size", "2").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[*].payoutId")
                        .value(contains(payoutIdsInCheckOrder.get(2), payoutIdsInCheckOrder.get(1))));
        mockMvc.perform(depositChecksRequest().param("page", "1").param("size", "2").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.content[*].payoutId").value(contains(payoutIdsInCheckOrder.get(0))));
    }

    @Test
    void 관리자_목록은_관리자_확인_필요_정산이_없으면_빈_목록이다() throws Exception {
        // when & then
        mockMvc.perform(depositChecksRequest().header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())                                               // 정산 상세(/{payoutId})로 잘못 들어가면 400
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void 관리자_목록은_토큰이_없으면_401이다() throws Exception {
        // when & then
        mockMvc.perform(depositChecksRequest())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_목록은_관리자가_아니면_403이다() throws Exception {
        // when & then
        mockMvc.perform(depositChecksRequest().header(HttpHeaders.AUTHORIZATION, tokens.bearer(10L, "BUYER", "SELLER")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("범위_밖_페이지_크기")
    void 관리자_목록은_페이지_크기가_1에서_100_밖이면_422다(String size) throws Exception {
        // when & then
        mockMvc.perform(depositChecksRequest().param("size", size)
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity());
    }

    // 아래·위 경계 바로 밖. 0은 하한 검사가 빠지면 쪽을 만드는 PageRequest가 거절해 422가 아닌 응답이 됨
    static Stream<String> 범위_밖_페이지_크기() {
        return Stream.of("0", "101");
    }

    @Test
    void 관리자_목록은_페이지_번호가_음수면_422다() throws Exception {
        // when & then
        mockMvc.perform(depositChecksRequest().param("page", "-1")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 도우미 ----------

    private MockHttpServletRequestBuilder depositChecksRequest() {
        return get("/api/v1/admin/payouts/deposit-checks");
    }

    // 정산 1건을 확정하고 그 정산 ID를 돌려줌 (지갑 입금 메시지도 이때 나감)
    private Long settleOnePayout(Long sellerId, Long amount) throws Exception {
        return settlePayouts(Map.of(sellerId, amount)).get(sellerId);
    }

    // 판매자마다 정산 1건씩 한 번의 Job으로 확정하고 판매자 → 정산 ID를 돌려줌
    private Map<Long, Long> settlePayouts(Map<Long, Long> amountBySeller) throws Exception {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(20);
        for (Map.Entry<Long, Long> entry : amountBySeller.entrySet()) {
            insertCandidate(PayoutEventType.SALE_AMOUNT, nextOrderId(), entry.getKey(), entry.getValue(), createdAt);
        }
        JobExecution execution = payoutJobRunner.runScheduled();
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<PayoutDepositCheck> checks = depositCheckRepository.findAll();
        assertThat(checks).hasSize(amountBySeller.size());
        Map<Long, Long> payoutIdBySeller = new HashMap<>();
        for (PayoutDepositCheck check : checks) {
            payoutIdBySeller.put(check.getPayeeId(), check.getPayoutId());
        }
        return payoutIdBySeller;
    }

    private PayoutOutbox outboxOf(Long payoutId) {
        List<PayoutOutbox> found = outboxRepository.findAllByPayoutIdIn(List.of(payoutId));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private PayoutDepositCheck checkOf(Long payoutId) {
        List<PayoutDepositCheck> found = depositCheckRepository.findAllByPayoutIdIn(List.of(payoutId));
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private void sendAnswer(Long checkId, Long payoutId, boolean found, Long payeeId, Long amount, String depositedAt) {
        Map<String, Object> message = Map.of("checkId", checkId,
                "items", List.of(answerItem(payoutId, found, payeeId, amount, depositedAt)));
        kafkaTemplate.send(PayoutTopics.WALLET_PAYOUT_DEPOSIT_CHECKED, String.valueOf(checkId), message);
    }

    // 지갑 답 항목 하나 (null 값을 넣을 수 있게 HashMap)
    private static Map<String, Object> answerItem(Long payoutId, boolean found, Long payeeId, Long amount, String depositedAt) {
        Map<String, Object> item = new HashMap<>();
        item.put("payoutId", payoutId);
        item.put("found", found);
        item.put("payeeId", payeeId);
        item.put("amount", amount);
        item.put("depositedAt", depositedAt);
        return item;
    }

    private String readMessage(String topic, String key) {
        return readMessage(topic, key, Map.of());
    }

    // 토픽을 처음(또는 from 위치)부터 지금 끝까지 읽어 그 키의 마지막 메시지를 JSON 문자열로 돌려줌. 없으면 null
    private String readMessage(String topic, String key, Map<TopicPartition, Long> from) {
        try (Consumer<?, ?> consumer = createConsumer()) {
            List<TopicPartition> partitions = assignTopic(consumer, topic);
            consumer.seekToBeginning(partitions);
            for (Map.Entry<TopicPartition, Long> entry : from.entrySet()) {
                consumer.seek(entry.getKey(), entry.getValue());
            }
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            String found = null;
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!reachedEnd(consumer, endOffsets) && System.nanoTime() < deadline) {
                for (ConsumerRecord<?, ?> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        found = (String) record.value();
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
        // JSON을 정산 클래스로 바꾸지 않고 글자 그대로 읽는다
        overrides.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 다른 컨슈머 그룹의 읽은 위치와 섞이지 않게 매번 새 그룹으로 읽는다
        return consumerFactory.createConsumer("payout-deposit-check-test-" + UUID.randomUUID(), null, null, overrides);
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

    // 정산 후보를 원하는 created_at으로 직접 저장 (+ 그 주문의 지갑 차감 기록). PayoutKafkaPublishIntegrationTest와 같은 SQL
    private void insertCandidate(PayoutEventType eventType, long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items (event_type, order_id, order_item_id, seller_id, amount, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                eventType.name(), orderId, orderId, sellerId, amount, createdAt, createdAt);
        givenDeduction(orderId);
    }

    private void insertAdjustment(long orderId, Long sellerId, Long amount, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO payout_candidate_items " +
                        "(event_type, order_id, order_item_id, seller_id, amount, adjustment_id, admin_id, reason, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                PayoutEventType.SALE_AMOUNT.name(), orderId, orderId, sellerId, amount, 1L, 99L, "테스트 조정",
                createdAt, createdAt);
        givenDeduction(orderId);
    }

    // 후보의 주문에 지갑 차감 기록을 넣음 (없으면 validateStep이 정산에서 뺌, #194). 금액은 넉넉한 고정값
    private void givenDeduction(long orderId) {
        if (payoutWalletDeductionRepository.existsByOrderId(orderId)) {
            return;
        }
        payoutWalletDeductionRepository.save(PayoutWalletDeduction.create(orderId, 1L, 1_000_000L, LocalDateTime.now()));
    }

    private long nextOrderId() {
        return ORDER_ID_SEQ.incrementAndGet();
    }

    // 정산·draft·스냅샷·대사는 전부, 정산 후보·차감 기록은 이 클래스 번호대만 지움 (FK 때문에 자식 테이블 먼저)
    // 질문 묶음 표는 대사 줄이 가리키므로 대사 줄 뒤에 지움 (리포지토리에는 삭제 기능이 없으므로 테스트 정리용으로만 SQL 직접 실행)
    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM payout_deposit_check");
        jdbcTemplate.update("DELETE FROM payout_deposit_check_batch");
        jdbcTemplate.update("DELETE FROM payout_outbox");
        jdbcTemplate.update("DELETE FROM payout_item");
        jdbcTemplate.update("DELETE FROM payout");
        jdbcTemplate.update("DELETE FROM payout_seller_draft_item");
        jdbcTemplate.update("DELETE FROM payout_seller_draft");
        jdbcTemplate.update("DELETE FROM payout_system_draft_item");
        jdbcTemplate.update("DELETE FROM payout_failure_resolution");
        jdbcTemplate.update("DELETE FROM payout_failure");
        jdbcTemplate.update("DELETE FROM payout_snapshot_item");
        jdbcTemplate.update("DELETE FROM payout_snapshot");
        jdbcTemplate.update("DELETE FROM payout_candidate_items WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
        jdbcTemplate.update("DELETE FROM payout_wallet_deduction WHERE order_id > ? AND order_id <= ?",
                ORDER_ID_START, ORDER_ID_END);
    }
}
