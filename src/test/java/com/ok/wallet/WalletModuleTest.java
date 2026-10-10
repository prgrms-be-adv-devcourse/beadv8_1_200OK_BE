package com.ok.wallet;

import com.ok.config.JpaConfig;
import com.ok.member.MemberRegisteredEvent;
import com.ok.member.MemberRole;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.repository.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class WalletModuleTest {
    @Autowired
    WalletRepository walletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다.
        walletRepository.deleteAll();
    }

    @Test
    void 회원가입_이벤트를_받으면_구매자_지갑이_생성된다(Scenario scenario) {
        // Given
        Long memberId = 1L;

        // When & Then
        scenario.publish(new MemberRegisteredEvent(memberId, MemberRole.BUYER))
                .andWaitForStateChange(() -> countBuyerWallets(memberId), count -> count == 1)
                .andVerify(count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void 같은_회원가입_이벤트를_두_번_받아도_지갑은_하나만_생성된다(Scenario scenario) {
        // Given
        Long memberId = 2L;
        MemberRegisteredEvent event = new MemberRegisteredEvent(memberId, MemberRole.BUYER);
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedEvents(memberId), count -> count == 1);

        // When
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedEvents(memberId), count -> count == 2);

        // Then
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
    }

    private long countBuyerWallets(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet WHERE member_id = ? AND type = 'BUYER'",
                Long.class, memberId);
    }

    // 해당 회원에 대한 MemberRegisteredEvent 중 리스너 처리가 완료된 건수
    private long countCompletedEvents(Long memberId) {
        return completedEventPublications.findAll().stream()
                .map(publication -> publication.getEvent())
                .filter(e -> e instanceof MemberRegisteredEvent registered
                        && registered.memberId().equals(memberId))
                .count();
    }
}
