package com.ok.wallet.repository;

import com.ok.common.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.domain.BuyerWallet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void 지갑이_없으면_insertIfAbsent가_잔액_0인_지갑을_저장한다() {
        // Given
        Long memberId = 2L;

        // When
        buyerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now());

        // Then
        BuyerWallet wallet = buyerWalletRepository.findForUpdateByMemberId(memberId).orElseThrow();
        assertThat(wallet.getBalance()).isZero();
    }

    @Test
    void 지갑이_이미_있으면_insertIfAbsent는_예외_없이_넘어간다() {
        // Given
        Long memberId = 3L;
        BuyerWallet existing = buyerWalletRepository.saveAndFlush(BuyerWallet.create(memberId));

        // When
        buyerWalletRepository.insertIfAbsent(memberId, LocalDateTime.now());

        // Then
        assertThat(buyerWalletRepository.findAll())
                .filteredOn(w -> w.getMemberId().equals(memberId))
                .singleElement()
                .extracting(BuyerWallet::getId)
                .isEqualTo(existing.getId());
    }
}
