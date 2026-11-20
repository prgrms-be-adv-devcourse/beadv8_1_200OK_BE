package com.ok.refund.adapter.in.scheduler;

import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 알람 잠금이 실제 DB 표(paldogotgan_refund.shedlock, 마이그레이션이 미리 넣은 행)로 동작하는지 본다.
// 알람은 테스트 프로필에서 빈으로 뜨지 않으므로(@Profile("!test")) 직접 만든다. 서버 두 대를 같은 DB를 쓰는 알람 둘로 흉내 낸다
class RefundResumeSchedulerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    // 다른 테스트에 잠금이 남지 않게 만료시킨다. 행은 지우지 않는다(지우면 ShedLock이 행이 있다고 기억해 다시 못 잡는다)
    @AfterEach
    void releaseLock() {
        new JdbcTemplate(dataSource).update(
                "UPDATE paldogotgan_refund.shedlock SET lock_until = UTC_TIMESTAMP(3) WHERE name IN (?, ?)",
                RefundResumeScheduler.LOCK_NAME, RefundAttentionScheduler.LOCK_NAME);
    }

    @Test
    void 한_서버가_잠금을_쥔_동안_다른_서버의_알람은_돌지_않는다() {
        // given: 같은 DB를 쓰는 서버 두 대의 알람
        AtomicInteger runs = new AtomicInteger();
        RefundResumeScheduler serverA = new RefundResumeScheduler(runs::incrementAndGet, dataSource);
        RefundResumeScheduler serverB = new RefundResumeScheduler(runs::incrementAndGet, dataSource);

        // when: A가 돌고 바로 B가 돈다(A는 끝난 뒤에도 최소 10초 잠금을 쥔다)
        serverA.run();
        serverB.run();

        // then
        assertThat(runs.get()).isEqualTo(1);
    }

    // 멈춘 환불 찾기 알람(#577)도 같은 잠금 표를 쓴다. 미리 넣은 잠금 행(마이그레이션)으로 한 서버만 돈다
    @Test
    void 멈춘_환불_찾기_알람도_한_서버만_돈다() {
        // given
        AtomicInteger runs = new AtomicInteger();
        RefundAttentionScheduler serverA = new RefundAttentionScheduler(runs::incrementAndGet, dataSource);
        RefundAttentionScheduler serverB = new RefundAttentionScheduler(runs::incrementAndGet, dataSource);

        // when
        serverA.run();
        serverB.run();

        // then
        assertThat(runs.get()).isEqualTo(1);
    }

    // 다시 진행 알람이 잠금을 쥐고 있어도 멈춘 환불 찾기 알람은 돈다(잠금 이름이 다르다)
    @Test
    void 두_알람은_서로의_잠금에_막히지_않는다() {
        // given
        AtomicInteger resumeRuns = new AtomicInteger();
        AtomicInteger attentionRuns = new AtomicInteger();

        // when
        new RefundResumeScheduler(resumeRuns::incrementAndGet, dataSource).run();
        new RefundAttentionScheduler(attentionRuns::incrementAndGet, dataSource).run();

        // then
        assertThat(resumeRuns.get() + attentionRuns.get()).isEqualTo(2);
    }
}
