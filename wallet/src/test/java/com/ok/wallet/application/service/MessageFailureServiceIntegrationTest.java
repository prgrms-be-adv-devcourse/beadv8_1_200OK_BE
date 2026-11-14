package com.ok.wallet.application.service;

import com.ok.testsupport.AbstractIntegrationTest;
import com.ok.wallet.application.dto.MessageFailureCommand;
import com.ok.wallet.application.port.in.MessageFailureUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 실제 표(마이그레이션)의 칸과 유니크 제약까지 확인한다
@ExtendWith(OutputCaptureExtension.class)
class MessageFailureServiceIntegrationTest extends AbstractIntegrationTest {
    private static final String TOPIC = "payout.seller-payout-created";
    private static final String PAYLOAD = "{\"payoutId\":12,\"payeeId\":5,\"amount\":0}";

    @Autowired
    MessageFailureUseCase messageFailureUseCase;

    @Autowired
    JdbcTemplate jdbcTemplate;

    // 실패 기록은 잔액과 관계가 없어 지갑 정리 도우미(WalletTestDataCleaner)가 다루지 않는다
    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM wallet_message_failure");
    }

    @Test
    void 처리하지_못한_메시지를_원문과_함께_대기_상태로_남긴다() {
        // given
        MessageFailureCommand command = new MessageFailureCommand(TOPIC, 1, 30L, "12", PAYLOAD,
                "IllegalArgumentException: amount가 0 이하");

        // when
        messageFailureUseCase.record(command);

        // then
        Map<String, Object> sut = jdbcTemplate.queryForMap("""
                SELECT topic, kafka_partition, kafka_offset, message_key, payload, failure_reason, status
                FROM wallet_message_failure
                """);
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.get("topic")).isEqualTo(TOPIC);
            softly.assertThat(sut.get("kafka_partition")).isEqualTo(1);
            softly.assertThat(sut.get("kafka_offset")).isEqualTo(30L);
            softly.assertThat(sut.get("message_key")).isEqualTo("12");
            softly.assertThat(sut.get("payload")).isEqualTo(PAYLOAD);
            softly.assertThat(sut.get("failure_reason")).isEqualTo("IllegalArgumentException: amount가 0 이하");
            softly.assertThat(sut.get("status")).isEqualTo("PENDING");
        });
    }

    @Test
    void 같은_메시지를_두_번_기록해도_처음_기록만_남는다() {
        // given: 기록 뒤 오프셋을 옮기기 전에 멈춰 같은 메시지가 다시 실패한 경우
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 30L, "12", PAYLOAD, "첫 실패"));

        // when
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 30L, "12", PAYLOAD, "두 번째 실패"));

        // then
        assertThat(jdbcTemplate.queryForList("SELECT failure_reason FROM wallet_message_failure", String.class))
                .containsExactly("첫 실패");
    }

    @Test
    void 같은_위치에_원문이_다른_메시지가_오면_처음_기록은_두고_표시가_붙은_ERROR_로그로_남긴다(CapturedOutput output) {
        // given: Kafka 볼륨만 지워 오프셋이 처음부터 다시 매겨진 경우처럼, 이미 기록된 위치에 다른 메시지가 실패해 옴
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 30L, "12", PAYLOAD, "첫 실패"));
        String otherPayload = "{\"payoutId\":99,\"payeeId\":5,\"amount\":0}";

        // when
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 30L, "99", otherPayload, "다른 메시지의 실패"));

        // then: 유니크 제약 때문에 새로 남길 수 없으므로, 묻히지 않게 원문을 담은 실패 표시 로그가 한 번 더 남는다
        assertThat(jdbcTemplate.queryForList("SELECT failure_reason FROM wallet_message_failure", String.class))
                .containsExactly("첫 실패");
        assertThat(output.getOut().split("\\[WALLET_MESSAGE_FAILED]", -1)).hasSize(3);
        assertThat(output.getOut()).contains(otherPayload);
    }

    @Test
    void 오프셋이_다르면_따로_남긴다() {
        // given
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 30L, "12", PAYLOAD, "실패"));

        // when
        messageFailureUseCase.record(new MessageFailureCommand(TOPIC, 1, 31L, "12", PAYLOAD, "실패"));

        // then
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_message_failure", Long.class))
                .isEqualTo(2L);
    }
}
