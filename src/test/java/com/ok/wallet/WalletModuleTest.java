package com.ok.wallet;

import com.ok.config.JpaConfig;
import com.ok.member.MemberRegisteredEvent;
import com.ok.member.MemberRole;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.WalletType;
import com.ok.wallet.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ApplicationModuleTest
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class WalletModuleTest {
    @Autowired
    WalletRepository walletRepository;

    @Test
    void 회원가입_이벤트를_받으면_구매자_지갑이_생성된다(Scenario scenario) {
        // Given
        Long memberId = 1L;

        // When & Then
        scenario.publish(new MemberRegisteredEvent(memberId, MemberRole.BUYER))
                .andWaitForStateChange(() -> walletRepository.existsByMemberIdAndType(memberId, WalletType.BUYER))
                .andVerify(exists -> assertThat(exists).isTrue());
    }

    @Test
    void 같은_회원가입_이벤트를_두_번_받아도_지갑은_하나만_생성된다(Scenario scenario) {
        // Given
        Long memberId = 2L;
        MemberRegisteredEvent event = new MemberRegisteredEvent(memberId, MemberRole.BUYER);
        scenario.publish(event)
                .andWaitForStateChange(() -> countBuyerWallets(memberId), count -> count == 1);

        // When
        scenario.publish(event)
                .andWaitForStateChange(() -> countBuyerWallets(memberId), count -> count >= 1);

        // Then : 두 번째 처리가 끝날 시간 동안 지갑이 계속 하나인지 확인
        await().during(Duration.ofMillis(500))
                .atMost(Duration.ofSeconds(5))
                .until(() -> countBuyerWallets(memberId) == 1);
    }

    // @ApplicationModuleTest는 롤백되지 않아 다른 테스트 데이터가 남으므로, 해당 회원의 지갑만 센다.
    private long countBuyerWallets(Long memberId) {
        return walletRepository.findAll().stream()
                .filter(wallet -> wallet.getMemberId().equals(memberId))
                .filter(wallet -> wallet.getType() == WalletType.BUYER)
                .count();
    }
}
