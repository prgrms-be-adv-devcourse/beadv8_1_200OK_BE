package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.BuyerWallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class BuyerWalletRepositoryTest {
    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Test
    void 지갑_중복_생성_실패_테스트() {
        // Given
        Long memberId = 1L;
        buyerWalletRepository.saveAndFlush(BuyerWallet.create(memberId));

        // When & Then
        assertThatThrownBy(() -> buyerWalletRepository.saveAndFlush(BuyerWallet.create(memberId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
