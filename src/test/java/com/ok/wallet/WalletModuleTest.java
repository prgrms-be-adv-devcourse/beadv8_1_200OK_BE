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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

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
}