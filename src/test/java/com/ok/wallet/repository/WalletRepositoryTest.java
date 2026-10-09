package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.Wallet;
import com.ok.wallet.domain.WalletType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class WalletRepositoryTest {
    @Autowired
    WalletRepository walletRepository;

    @Test
    void 지갑_중복_생성_실패_테스트() {
        // Arrange
        Long memberId = 1L;
        WalletType type = WalletType.BUYER;
        walletRepository.saveAndFlush(Wallet.create(memberId, type));

        // Act & Assert
        assertThatThrownBy(() -> walletRepository.saveAndFlush(Wallet.create(memberId, type)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}