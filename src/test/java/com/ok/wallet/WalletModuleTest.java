package com.ok.wallet;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.common.event.SellerApprovedEvent;
import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.config.JpaConfig;
import com.ok.testsupport.TestcontainersConfiguration;
import com.ok.wallet.repository.BuyerWalletRepository;
import com.ok.wallet.repository.SellerWalletRepository;
import com.ok.wallet.service.BuyerWalletService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@Import({JpaConfig.class, TestcontainersConfiguration.class})
class WalletModuleTest {
    // 실제 정산 ID와 겹치지 않는 이 클래스 전용 번호대
    private static final long PAYOUT_ID = 9_200_001L;

    @Autowired
    BuyerWalletRepository buyerWalletRepository;

    @Autowired
    SellerWalletRepository sellerWalletRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    CompletedEventPublications completedEventPublications;

    @Autowired
    BuyerWalletService buyerWalletService;

    @AfterEach
    void tearDown() {
        // @ApplicationModuleTest는 롤백되지 않으므로 테스트마다 데이터를 정리한다.
        // 원장은 지갑을 FK로 참조하므로 먼저 지운다. 원장 저장소에는 삭제 메서드가 없어 SQL로 정리한다.
        jdbcTemplate.update("DELETE FROM buyer_wallet_ledger");
        jdbcTemplate.update("DELETE FROM seller_wallet_ledger");
        buyerWalletRepository.deleteAll();
        sellerWalletRepository.deleteAll();
    }

    @Test
    void 회원가입_이벤트를_받으면_구매자_지갑이_생성된다(Scenario scenario) {
        // Given
        Long memberId = 1L;

        // When & Then
        scenario.publish(new MemberRegisteredEvent(memberId))
                .andWaitForStateChange(() -> countBuyerWallets(memberId), count -> count == 1)
                .andVerify(count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void 같은_회원가입_이벤트를_두_번_받아도_지갑은_하나만_생성된다(Scenario scenario) {
        // Given
        Long memberId = 2L;
        MemberRegisteredEvent event = new MemberRegisteredEvent(memberId);
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

    @Test
    void 요청으로_지갑이_먼저_생성된_뒤_회원가입_이벤트가_와도_지갑은_하나다(Scenario scenario) {
        // Given: 이벤트보다 먼저 요청 시점 보완 생성으로 지갑이 만들어짐
        Long memberId = 6L;
        buyerWalletService.getOrCreate(memberId);

        // When: 이벤트 처리가 완료될 때까지 기다림
        scenario.publish(new MemberRegisteredEvent(memberId))
                .andWaitForStateChange(() -> countCompletedEvents(memberId), count -> count == 1);

        // Then
        assertThat(countBuyerWallets(memberId)).isEqualTo(1);
    }

    // 해당 회원에 대한 MemberRegisteredEvent 중 리스너 처리가 완료된 건수
    // 완료된 이벤트 발행 기록(event_publication)이 남아 있다는 전제에 의존한다.
    // 완료 기록을 삭제하도록 설정이 바뀌면, 중복 이벤트 테스트에서 처리 완료를 기다리는 방식을 바꿔야 한다.
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
        scenario.publish(new MemberRegisteredEvent(memberId))
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
    // 완료된 이벤트 발행 기록(event_publication)이 남아 있다는 전제에 의존한다.
    // 완료 기록을 삭제하도록 설정이 바뀌면, 중복 이벤트 테스트에서 처리 완료를 기다리는 방식을 바꿔야 한다.
    private long countCompletedSellerApprovedEvents(Long memberId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof SellerApprovedEvent approved
                        && approved.memberId().equals(memberId))
                .count();
    }

    @Test
    void 정산_생성_이벤트를_받으면_판매자_지갑에_입금된다(Scenario scenario) {
        // Given
        Long sellerId = 20L;

        // When & Then
        scenario.publish(new SellerPayoutCreatedEvent(PAYOUT_ID, sellerId, 9_700L, LocalDateTime.now()))
                .andWaitForStateChange(() -> sellerBalanceOf(sellerId), balance -> balance == 9_700)
                .andVerify(balance -> assertThat(balance).isEqualTo(9_700));
    }

    @Test
    void 같은_정산_이벤트를_두_번_받아도_한_번만_입금된다(Scenario scenario) {
        // Given
        Long sellerId = 21L;
        long payoutId = PAYOUT_ID + 1;
        SellerPayoutCreatedEvent event = new SellerPayoutCreatedEvent(payoutId, sellerId, 9_700L, LocalDateTime.now());
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedPayoutEvents(payoutId), count -> count == 1);

        // When
        scenario.publish(event)
                .andWaitForStateChange(() -> countCompletedPayoutEvents(payoutId), count -> count == 2);

        // Then
        assertThat(sellerBalanceOf(sellerId)).isEqualTo(9_700);
    }

    @Test
    void 금액이_0_이하인_정산_이벤트는_입금하지_않고_처리를_끝낸다(Scenario scenario) {
        // Given
        Long sellerId = 22L;
        long payoutId = PAYOUT_ID + 2;

        // When: 처리 불가 이벤트도 완료로 처리되어 재처리 대상에 남지 않는지 기다림
        scenario.publish(new SellerPayoutCreatedEvent(payoutId, sellerId, 0L, LocalDateTime.now()))
                .andWaitForStateChange(() -> countCompletedPayoutEvents(payoutId), count -> count == 1);

        // Then: 서비스까지 가지 않아 지갑도 만들어지지 않는다
        assertThat(countSellerWallets(sellerId)).isZero();
    }

    private long sellerBalanceOf(Long memberId) {
        // 지갑이 아직 없으면 0으로 본다 (리스너가 처리 중일 수 있음)
        List<Long> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM seller_wallet WHERE member_id = ?", Long.class, memberId);
        return balances.isEmpty() ? 0 : balances.get(0);
    }

    // 해당 정산에 대한 SellerPayoutCreatedEvent 중 리스너 처리가 완료된 건수
    private long countCompletedPayoutEvents(Long payoutId) {
        return completedEventPublications.findAll().stream()
                .map(EventPublication::getEvent)
                .filter(e -> e instanceof SellerPayoutCreatedEvent payout
                        && payoutId.equals(payout.payoutId()))
                .count();
    }
}
