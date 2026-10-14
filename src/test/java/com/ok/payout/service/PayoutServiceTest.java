package com.ok.payout.service;

import com.ok.common.event.SellerPayoutCreatedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutErrorCode;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutQueryRepository.CountAndAmount;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
// 분기·실패·이벤트 발행만 확인하고, DB가 필요한 정상 흐름(스냅샷 → 확정)은 PayoutSettleIntegrationTest에서 확인
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    private static final Long SNAPSHOT_ID = 12L;

    @Mock
    private PayoutQueryRepository payoutQueryRepository;

    @Mock
    private PayoutRepository payoutRepository;

    @Mock
    private PayoutSnapshotRepository payoutSnapshotRepository;

    @Mock
    private PayoutSnapshotItemRepository payoutSnapshotItemRepository;

    @Mock
    private PayoutSellerDraftRepository payoutSellerDraftRepository;

    @Mock
    private PayoutSystemDraftItemRepository payoutSystemDraftItemRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private PayoutService sut;

    @Captor
    private ArgumentCaptor<List<PayoutSellerDraft>> sellerDraftsCaptor;

    @Captor
    private ArgumentCaptor<List<PayoutSystemDraftItem>> systemItemsCaptor;

    @Captor
    private ArgumentCaptor<SellerPayoutCreatedEvent> eventCaptor;

    // ---------- draft ----------

    @Test
    void 판매자_몫은_판매자별_draft로_수수료는_시스템_draft_항목으로_나눠_저장한다() {
        // given: 판매자 10(대금·수수료·배송비), 판매자 20(대금·수수료)
        List<Long> sellerIds = List.of(10L, 20L);
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUndraftedCandidates(SNAPSHOT_ID, sellerIds)).willReturn(List.of(
                withId(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleFee(1001L, 10L, 300L), 2L),
                withId(PayoutCandidateItem.ofSaleShippingFee(1001L, 10L, 3_000L), 3L),
                withId(PayoutCandidateItem.ofSaleAmount(2001L, 20L, 19_400L), 4L),
                withId(PayoutCandidateItem.ofSaleFee(2001L, 20L, 600L), 5L)));

        // when
        int drafted = sut.draft(SNAPSHOT_ID, 100);

        // then: 처리한 판매자 수 (시스템 몫은 판매자 수에 안 셈)
        assertThat(drafted).isEqualTo(2);

        then(payoutSellerDraftRepository).should().saveAll(sellerDraftsCaptor.capture());
        assertThat(sellerDraftsCaptor.getValue())
                .extracting(PayoutSellerDraft::getSellerId, PayoutSellerDraft::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(10L, 12_700L),    // 대금 9,700 + 배송비 3,000
                        tuple(20L, 19_400L));   // 대금만

        then(payoutSystemDraftItemRepository).should().saveAll(systemItemsCaptor.capture());
        assertThat(systemItemsCaptor.getValue())
                .extracting(PayoutSystemDraftItem::getCandidateId, PayoutSystemDraftItem::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(2L, 300L),
                        tuple(5L, 600L));
    }

    @Test
    void 처리할_판매자가_없으면_0을_반환하고_저장하지_않는다() {
        // given
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(List.of());

        // when
        int drafted = sut.draft(SNAPSHOT_ID, 100);

        // then: 0을 반환하면 tasklet이 FINISHED로 끝남
        // 처리할 판매자가 없으면 저장하지 않도록 일단 정해 두어, 저장 호출이 없는지 확인
        assertThat(drafted).isZero();
        then(payoutSellerDraftRepository).should(never()).saveAll(any());
        then(payoutSystemDraftItemRepository).should(never()).saveAll(any());
    }

    @Test
    void 판매자는_뽑혔는데_후보가_없으면_무한_반복_대신_예외로_멈춘다() {
        // given: 두 조회 조건이 어긋난 상황 (판매자 조회 결과는 있는데 후보 조회 결과가 없음)
        List<Long> sellerIds = List.of(10L);
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUndraftedCandidates(SNAPSHOT_ID, sellerIds)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.draft(SNAPSHOT_ID, 100))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.UNSETTLED_CANDIDATES_NOT_FOUND));
    }

    // ---------- verify ----------

    @Test
    void 스냅샷과_draft의_건수_금액이_같고_판매자_헤더_합계가_맞으면_통과한다() {
        // given: 스냅샷 3건 10,000원 = 판매자 항목 2건 9,700원 + 시스템 항목 1건 300원
        givenSummaries(new CountAndAmount(3, 10_000), new CountAndAmount(2, 9_700), new CountAndAmount(1, 300), 9_700);

        // when & then
        assertThatCode(() -> sut.verify(SNAPSHOT_ID)).doesNotThrowAnyException();
    }

    @Test
    void 스냅샷보다_draft가_적으면_검증에_실패한다() {
        // given: 스냅샷 3건인데 draft는 2건 (1건 누락)
        givenSummaries(new CountAndAmount(3, 10_000), new CountAndAmount(1, 9_700), new CountAndAmount(1, 300), 9_700);
        given(payoutQueryRepository.findUndraftedCandidateIds(SNAPSHOT_ID, 100)).willReturn(List.of(7L));

        // when & then
        assertThatThrownBy(() -> sut.verify(SNAPSHOT_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.DRAFT_VERIFICATION_FAILED));
    }

    @Test
    void 판매자_헤더_합계가_항목_합계와_다르면_검증에_실패한다() {
        // given: 항목 건수·금액은 맞는데 판매자 헤더 합계만 다름 (헤더 합계 계산 버그)
        givenSummaries(new CountAndAmount(3, 10_000), new CountAndAmount(2, 9_700), new CountAndAmount(1, 300), 9_000);

        // when & then
        assertThatThrownBy(() -> sut.verify(SNAPSHOT_ID))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.DRAFT_VERIFICATION_FAILED));
    }

    // ---------- publish ----------

    @Test
    void 조회한_판매자_몫_정산마다_지갑_이벤트를_1건씩_발행한다() {
        // given: 이번 스냅샷의 판매자 몫 정산 2건 (시스템 몫 제외는 조회 쿼리가 맡음 → 통합 테스트에서 확인)
        Payout seller10 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 10L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L), 1L))));
        Payout seller20 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 20L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(2001L, 20L, 19_400L), 4L))));
        given(payoutRepository.findBySnapshotIdAndSystemFalse(SNAPSHOT_ID)).willReturn(List.of(seller10, seller20));

        // when
        int published = sut.publish(SNAPSHOT_ID);

        // then: payoutId·createdAt은 저장 후 채워지는 값이라 단위 테스트에서는 확인하지 않음
        assertThat(published).isEqualTo(2);
        then(eventPublisher).should(times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(SellerPayoutCreatedEvent::payeeId, SellerPayoutCreatedEvent::amount)
                .containsExactlyInAnyOrder(
                        tuple(10L, 9_700L),
                        tuple(20L, 19_400L));
    }

    // ---------- settlementTo / settlementFrom ----------

    @Test
    void 조회_상한은_실행일에서_15일_전_0시다() {
        // when
        LocalDateTime to = sut.settlementTo(LocalDate.of(2026, 10, 10));

        // then: 10/10 − 15일 = 09/25 00:00
        assertThat(to).isEqualTo(LocalDateTime.of(2026, 9, 25, 0, 0));
    }

    @Test
    void 정산이_한_건도_없으면_조회_하한은_null이다() {
        // given: 첫 실행
        given(payoutRepository.findFirstByOrderByIdDesc()).willReturn(Optional.empty());

        // when
        LocalDateTime from = sut.settlementFrom();

        // then
        assertThat(from).isNull();
    }

    @Test
    void 조회_하한은_마지막_정산일에서_대기_15일과_여유_3일을_뺀_0시다() {
        // given: 마지막 정산이 10/09 02:00에 생성됨
        // Payout.forSeller()로 만든 객체는 created_at이 비어 있으므로(저장 시 Auditing이 채움) 가짜 Payout을 사용
        Payout lastPayout = mock(Payout.class);
        given(lastPayout.getCreatedAt()).willReturn(LocalDateTime.of(2026, 10, 9, 2, 0));
        given(payoutRepository.findFirstByOrderByIdDesc()).willReturn(Optional.of(lastPayout));

        // when
        LocalDateTime from = sut.settlementFrom();

        // then: 10/09 − 18일 = 09/21 00:00 (시각 02:00은 버리고 날짜 시작으로)
        assertThat(from).isEqualTo(LocalDateTime.of(2026, 9, 21, 0, 0));
    }

    // ---------- 도우미 ----------

    // verify가 비교할 집계값을 한 번에 준비 (스냅샷, 판매자 항목, 시스템 항목, 판매자 헤더 금액 합계)
    private void givenSummaries(CountAndAmount snapshot, CountAndAmount sellerItems,
                                CountAndAmount systemItems, long sellerHeaderAmount) {
        given(payoutQueryRepository.summarizeSnapshotItems(SNAPSHOT_ID)).willReturn(snapshot);
        given(payoutQueryRepository.summarizeSellerDraftItems(SNAPSHOT_ID)).willReturn(sellerItems);
        given(payoutQueryRepository.summarizeSystemDraftItems(SNAPSHOT_ID)).willReturn(systemItems);
        given(payoutQueryRepository.sumSellerDraftAmount(SNAPSHOT_ID)).willReturn(sellerHeaderAmount);
    }

    // 저장된 후보처럼 id를 채움 (setId가 없고 id는 DB 저장 시 채워지므로 단위 테스트에서 직접 넣음)
    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
