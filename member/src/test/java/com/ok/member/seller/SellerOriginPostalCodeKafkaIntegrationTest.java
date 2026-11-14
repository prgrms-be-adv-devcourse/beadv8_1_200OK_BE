package com.ok.member.seller;

import com.ok.member.KafkaTopicReader;
import com.ok.member.MemberSchema;
import com.ok.member.member.adapter.out.event.FailableMemberMessageSender;
import com.ok.member.member.adapter.out.event.MemberTopics;
import com.ok.member.member.adapter.out.persistence.MemberJpaRepository;
import com.ok.member.member.adapter.out.persistence.MemberOutboxJpaRepository;
import com.ok.member.member.application.port.in.MemberOutboxRecordUseCase;
import com.ok.member.member.application.port.in.MemberOutboxRelayUseCase;
import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberOutbox;
import com.ok.member.member.domain.MemberOutboxEventType;
import com.ok.member.member.domain.MemberOutboxStatus;
import com.ok.member.seller.adapter.out.persistence.SellerProfileJpaRepository;
import com.ok.member.seller.application.dto.SellerProfileCommand;
import com.ok.member.seller.application.port.in.SellerProfileUseCase;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 통합 테스트: 판매자 프로필 등록·수정으로 출고지 우편번호가 바뀌면 회원 outbox에 줄이 남고, 발송 작업이 실제 Kafka 토픽에
// delivery와 약속한 모양(sellerId, postalCode, occurredAt)으로 sellerId를 키로 보내는지 확인한다 (#587)
// - 발송 스케줄러는 테스트에서 꺼져 있어(module-test.yml) 발송 유스케이스를 직접 부른다
// - 다른 테스트의 메시지와 섞이지 않게 토픽을 키(sellerId)로 골라 센다
class SellerOriginPostalCodeKafkaIntegrationTest extends AbstractIntegrationTest {

    private static final int BATCH_SIZE = 100;
    private static final Duration READ_DURATION = Duration.ofSeconds(3);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 18, 14, 30);

    @Autowired
    SellerProfileUseCase sellerProfileUseCase;

    @Autowired
    MemberOutboxRelayUseCase memberOutboxRelayUseCase;

    @Autowired
    MemberOutboxRecordUseCase memberOutboxRecordUseCase;

    @Autowired
    FailableMemberMessageSender failableMemberMessageSender;

    @Autowired
    MemberJpaRepository memberRepository;

    @Autowired
    SellerProfileJpaRepository sellerProfileRepository;

    @Autowired
    MemberOutboxJpaRepository memberOutboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    private KafkaTopicReader reader;

    private Long sellerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        reader = new KafkaTopicReader(kafkaConnectionDetails.getBootstrapServers(), MemberTopics.SELLER_ORIGIN_POSTAL_CODE);
        sellerId = memberRepository.saveAndFlush(Member.createUser("seller01", "{bcrypt}encoded",
                "seller01@example.com", "김영수", "seller01", AT)).getId();
    }

    @AfterEach
    void tearDown() {
        failableMemberMessageSender.reset();
        reader.close();
        cleanUp();
    }

    @Test
    void 프로필을_등록하면_약속한_모양의_메시지가_판매자_키로_한_건_나간다() {
        // given
        sellerProfileUseCase.register(sellerId, command("37421"));

        // when
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 받는 쪽은 JSON 모양으로 읽으므로 필드 이름과 값 모양을 그대로 확인한다
        assertThat(sent).isEqualTo(1);
        List<String> values = reader.readValuesFor(sellerId, READ_DURATION);
        assertThat(values).hasSize(1);
        JsonNode sut = jsonMapper.readTree(values.getFirst());
        assertSoftly(softly -> {
            softly.assertThat(sut.propertyNames()).containsExactlyInAnyOrder("sellerId", "postalCode", "occurredAt");
            softly.assertThat(sut.get("sellerId").asLong()).isEqualTo(sellerId);
            softly.assertThat(sut.get("postalCode").asString()).isEqualTo("37421");
            // 시간대 표기 없는 한국 시간(정산 → 지갑 메시지와 같은 모양)이라 LocalDateTime으로 그대로 읽혀야 한다
            softly.assertThatCode(() -> LocalDateTime.parse(sut.get("occurredAt").asString())).doesNotThrowAnyException();
        });
        assertThat(memberOutboxRepository.findAll().getFirst().getStatus()).isEqualTo(MemberOutboxStatus.SENT);
    }

    @Test
    void 출고지_우편번호가_바뀌면_새_값으로_다시_보낸다() {
        // given
        sellerProfileUseCase.register(sellerId, command("37421"));
        memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // when
        sellerProfileUseCase.update(sellerId, command("06236"));
        memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 같은 판매자 키로 등록 때 값과 바뀐 값이 순서대로 나간다
        List<String> sut = reader.readValuesFor(sellerId, READ_DURATION);
        assertThat(sut).hasSize(2);
        assertSoftly(softly -> {
            softly.assertThat(sut.get(0)).contains("\"postalCode\":\"37421\"");
            softly.assertThat(sut.get(1)).contains("\"postalCode\":\"06236\"");
        });
    }

    @Test
    void 출고지_우편번호를_그대로_두고_수정하면_outbox_줄이_늘지_않는다() {
        // given
        sellerProfileUseCase.register(sellerId, command("37421"));

        // when
        sellerProfileUseCase.update(sellerId, command("37421"));

        // then
        assertThat(memberOutboxRepository.findAll()).hasSize(1);
    }

    @Test
    void 우편번호_항목이_생기기_전에_만든_프로필은_처음_입력할_때_보낸다() {
        // given: 이 컬럼이 생기기 전 프로필처럼 출고지가 비어 있다
        sellerProfileUseCase.register(sellerId, command("37421"));
        memberOutboxRepository.deleteAllInBatch();
        jdbcTemplate.update("UPDATE " + MemberSchema.NAME + ".seller_profile SET origin_postal_code = NULL WHERE member_id = ?",
                sellerId);

        // when
        sellerProfileUseCase.update(sellerId, command("37421"));

        // then
        List<MemberOutbox> sut = memberOutboxRepository.findAll();
        assertThat(sut).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(sut.getFirst().getEventType()).isEqualTo(MemberOutboxEventType.SELLER_ORIGIN_POSTAL_CODE_CHANGED);
            softly.assertThat(sut.getFirst().getMemberId()).isEqualTo(sellerId);
            softly.assertThat(sut.getFirst().getPayload()).contains("\"postalCode\":\"37421\"");
        });
    }

    @Test
    void 프로필_저장_트랜잭션이_롤백되면_outbox_줄도_남지_않는다() {
        // given: 등록을 바깥 트랜잭션 안에서 부르고 끝에 롤백한다 (등록은 바깥 트랜잭션에 합류한다)
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // when
        transaction.executeWithoutResult(status -> {
            sellerProfileUseCase.register(sellerId, command("37421"));
            status.setRollbackOnly();
        });

        // then
        assertSoftly(softly -> {
            softly.assertThat(memberOutboxRepository.findAll()).isEmpty();
            softly.assertThat(sellerProfileRepository.existsByMemberId(sellerId)).isFalse();
        });
    }

    @Test
    void outbox_기록은_트랜잭션_없이_부르면_거부한다() {
        // when & then: 프로필 저장과 따로 커밋되면 롤백된 변경이 나갈 수 있어 호출한 쪽의 트랜잭션을 강제한다
        assertThatThrownBy(() -> memberOutboxRecordUseCase.recordSellerOriginPostalCodeChanged(sellerId, "37421", AT))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(memberOutboxRepository.findAll()).isEmpty();
    }

    @Test
    void 브로커가_일시적으로_실패해도_다음_실행에서_같은_값으로_다시_보낸다() {
        // given: 첫 발송은 브로커 장애로 실패한다
        sellerProfileUseCase.register(sellerId, command("37421"));
        failableMemberMessageSender.failNextSend(new IllegalStateException("출고지 우편번호 Kafka 발행에 실패했습니다."));
        int sentWhileFailing = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // when
        int sent = memberOutboxRelayUseCase.relayPending(BATCH_SIZE);

        // then: 기록할 때 저장한 본문이 그대로 나간다
        assertSoftly(softly -> {
            softly.assertThat(sentWhileFailing).isZero();
            softly.assertThat(sent).isEqualTo(1);
        });
        List<String> values = reader.readValuesFor(sellerId, READ_DURATION);
        assertThat(values).hasSize(1);
        assertThat(values.getFirst()).contains("\"postalCode\":\"37421\"");
    }

    private static SellerProfileCommand command(String originPostalCode) {
        return new SellerProfileCommand("청송 햇살농원", "김영수", "054-873-2210", originPostalCode, null, null);
    }

    // outbox 줄과 판매자 프로필이 member를 외래키로 참조하므로 먼저 지운다
    private void cleanUp() {
        memberOutboxRepository.deleteAllInBatch();
        sellerProfileRepository.deleteAllInBatch();
        memberRepository.deleteAllInBatch();
    }
}
