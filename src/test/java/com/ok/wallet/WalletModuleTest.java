package com.ok.wallet;

import com.ok.config.JpaConfig;
import com.ok.member.MemberRegisteredEvent;
import com.ok.member.MemberRole;
import com.ok.member.SellerApprovedEvent;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.repository.BuyerWalletRepository;
import com.ok.wallet.repository.SellerWalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class WalletModuleTest {
    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Autowired
    SellerWalletRepository sellerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다.
        buyerWalletRepository.deleteAll();
        sellerWalletRepository.deleteAll();
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
                "SELECT COUNT(*) FROM buyer_wallet WHERE member_id = ?",
                Long.class, memberId);
    }

    // 해당 회원에 대한 MemberRegisteredEvent 중 리스너 처리가 완료된 건수
    private long countCompletedEvents(Long memberId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof MemberRegisteredEvent registered
                        && registered.memberId().equals(memberId))
                .count();
    }

    @Test
    void 판매자_승인_이벤트를_받으면_판매자_지갑이_생성된다(Scenario scenario) {
        // Given
        Long memberId = 3L;

        // When & Then
        scenario.publish(new SellerApprovedEvent(memberId, LocalDateTime.now()))
                .andWaitForStateChange(() -> countSellerWallets(memberId), count -> count == 1)
                .andVerify(count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void 같은_판매자_승인_이벤트를_두_번_받아도_판매자_지갑은_하나만_생성된다(Scenario scenario) {
        // Given
        Long memberId = 4L;
        SellerApprovedEvent event = new SellerApprovedEvent(memberId, LocalDateTime.now());
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedSellerApprovedEvents(memberId), count -> count == 1);

        // When
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedSellerApprovedEvents(memberId), count -> count == 2);

        // Then
        assertThat(countSellerWallets(memberId)).isEqualTo(1);
    }

    @Test
    void 회원가입_이벤트로는_판매자_지갑이_생성되지_않는다(Scenario scenario) {
        // Given
        Long memberId = 5L;

        // When: 구매자 지갑이 생성될 때까지 기다려 이벤트 처리가 끝났음을 확인
        scenario.publish(new MemberRegisteredEvent(memberId, MemberRole.SELLER))
                .andWaitForStateChange(() -> countBuyerWallets(memberId), count -> count == 1);

        // Then
        assertThat(countSellerWallets(memberId)).isZero();
    }

    private long countSellerWallets(Long memberId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seller_wallet WHERE member_id = ?",
                Long.class, memberId);
    }

    // 해당 회원에 대한 SellerApprovedEvent 중 리스너 처리가 완료된 건수
    private long countCompletedSellerApprovedEvents(Long memberId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof SellerApprovedEvent approved
                        && approved.memberId().equals(memberId))
                .count();
    }
}
