package com.ok.wallet.repository;

import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.SellerWallet;
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
class SellerWalletRepositoryTest {
    @Autowired
    SellerWalletRepository sellerWalletRepository;

    @Test
    void 판매자_지갑_중복_생성_실패_테스트() {
        // Given
        Long memberId = 1L;
        sellerWalletRepository.saveAndFlush(SellerWallet.create(memberId));

        // When & Then
        assertThatThrownBy(() -> sellerWalletRepository.saveAndFlush(SellerWallet.create(memberId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
