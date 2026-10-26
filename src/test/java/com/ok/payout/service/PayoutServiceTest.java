package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.Payout;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSnapshot;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutQueryRepository;
import com.ok.payout.repository.PayoutQueryRepository.CountAndAmount;
import com.ok.payout.repository.PayoutRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSnapshotItemRepository;
import com.ok.payout.repository.PayoutSnapshotRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import com.ok.payout.service.dto.PayoutAdjustmentCreateCommand;
import com.ok.payout.service.dto.PayoutDetailResult;
import com.ok.payout.service.dto.PayoutDraftResult;
import com.ok.payout.service.dto.PayoutItemSearchQuery;
import com.ok.payout.service.dto.PayoutSearchQuery;
import com.ok.payout.service.dto.PayoutSellerSearchQuery;
import com.ok.payout.service.dto.PayoutSummaryResult;
import com.ok.payout.service.dto.SellerPayoutCreatedCommand;
import com.ok.payout.service.publisher.PayoutEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
// 분기·실패·이벤트 발행만 확인하고, DB가 필요한 정상 흐름(스냅샷 → 확정)은 PayoutSettleIntegrationTest에서 확인
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    private static final Long SNAPSHOT_ID = 12L;
    // 상세 조회 테스트의 스냅샷 대상 기간
    private static final LocalDateTime PERIOD_FROM = LocalDateTime.of(2026, 9, 27, 0, 0);
    private static final LocalDateTime PERIOD_TO = LocalDateTime.of(2026, 9, 30, 0, 0);

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
    private PayoutSellerDraftService payoutSellerDraftService;

    @Mock
    private PayoutEventPublisher payoutEventPublisher;

    @InjectMocks
    private PayoutService sut;

    @Captor
    private ArgumentCaptor<SellerPayoutCreatedCommand> commandCaptor;

    // ---------- draft ----------

    @Test
    void 판매자마다_그_판매자의_후보_전체를_따로_저장한다() {
        // given: 판매자 10(대금·수수료·배송비), 판매자 20(대금·수수료)
        List<Long> sellerIds = List.of(10L, 20L);
        PayoutCandidateItem amount10 = withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L);
        PayoutCandidateItem fee10 = withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 2L);
        PayoutCandidateItem shipping10 = withId(PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L), 3L);
        PayoutCandidateItem amount20 = withId(PayoutCandidateItem.ofSaleAmount(2L, 2001L, 20L, 19_400L), 4L);
        PayoutCandidateItem fee20 = withId(PayoutCandidateItem.ofSaleFee(2L, 2001L, 20L, 600L), 5L);
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUndraftedCandidates(SNAPSHOT_ID, sellerIds))
                .willReturn(List.of(amount10, fee10, shipping10, amount20, fee20));

        // when
        PayoutDraftResult drafted = sut.draft(SNAPSHOT_ID, 100);

        // then: 판매자마다 한 번씩, 수수료까지 그 판매자 후보를 모두 넘김 (판매자 몫·수수료 분류는 PayoutSellerDraftService가 함)
        // 호출 단위가 곧 트랜잭션 단위라 판매자별로 나눠 부르는 것 자체가 요구사항 (testing-strategy.md "호출 횟수는 요구사항일 때만")
        assertThat(drafted).isEqualTo(new PayoutDraftResult(2, 0));
        then(payoutSellerDraftService).should().saveDraft(SNAPSHOT_ID, 10L, List.of(amount10, fee10, shipping10));
        then(payoutSellerDraftService).should().saveDraft(SNAPSHOT_ID, 20L, List.of(amount20, fee20));
        then(payoutSellerDraftService).should(never()).recordUnexpectedError(any(), any(), any());
    }

    @Test
    void 한_판매자_저장이_실패하면_그_판매자만_실패로_기록하고_다른_판매자는_계속_저장한다() {
        // given: 판매자 10 저장에서 예외, 판매자 20은 정상
        List<Long> sellerIds = List.of(10L, 20L);
        PayoutCandidateItem amount10 = withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L);
        PayoutCandidateItem fee10 = withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 2L);
        PayoutCandidateItem amount20 = withId(PayoutCandidateItem.ofSaleAmount(2L, 2001L, 20L, 19_400L), 4L);
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUndraftedCandidates(SNAPSHOT_ID, sellerIds))
                .willReturn(List.of(amount10, fee10, amount20));
        IllegalStateException failure = new IllegalStateException("판매자 10 저장 실패");
        willThrow(failure).given(payoutSellerDraftService).saveDraft(SNAPSHOT_ID, 10L, List.of(amount10, fee10));

        // when
        PayoutDraftResult drafted = sut.draft(SNAPSHOT_ID, 100);

        // then: 예외를 밖으로 던지지 않고, 판매자 10 후보만 실패로 기록하고, 판매자 20은 저장함
        // 실패한 판매자도 판매자 수에 셈 (0이 아니어야 tasklet이 다음 회차를 부름), 실패 수는 따로 1
        assertThat(drafted).isEqualTo(new PayoutDraftResult(2, 1));
        then(payoutSellerDraftService).should().recordUnexpectedError(SNAPSHOT_ID, List.of(amount10, fee10), failure);
        then(payoutSellerDraftService).should().saveDraft(SNAPSHOT_ID, 20L, List.of(amount20));
    }

    @Test
    void 실패_기록_저장마저_실패하면_예외가_그대로_나간다() {
        // given: 판매자 10 저장도, 그 실패 기록 저장도 실패
        List<Long> sellerIds = List.of(10L);
        PayoutCandidateItem amount10 = withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L);
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(sellerIds);
        given(payoutQueryRepository.findUndraftedCandidates(SNAPSHOT_ID, sellerIds)).willReturn(List.of(amount10));
        IllegalStateException failure = new IllegalStateException("판매자 10 저장 실패");
        willThrow(failure).given(payoutSellerDraftService).saveDraft(SNAPSHOT_ID, 10L, List.of(amount10));
        willThrow(new IllegalStateException("실패 기록 저장 실패"))
                .given(payoutSellerDraftService).recordUnexpectedError(SNAPSHOT_ID, List.of(amount10), failure);

        // when & then: 기록 없이 넘어가면 그 후보가 draft에도 실패에도 없어 verify에서 Job이 멈추므로, 여기서 바로 멈춤
        assertThatThrownBy(() -> sut.draft(SNAPSHOT_ID, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("실패 기록 저장 실패");
    }

    @Test
    void 처리할_판매자가_없으면_0을_반환하고_저장하지_않는다() {
        // given
        given(payoutQueryRepository.findUndraftedSellerIds(SNAPSHOT_ID, 100)).willReturn(List.of());

        // when
        PayoutDraftResult drafted = sut.draft(SNAPSHOT_ID, 100);

        // then: 0을 반환하면 tasklet이 FINISHED로 끝남
        // 처리할 판매자가 없으면 저장하지 않도록 일단 정해 두어, 저장 호출이 없는지 확인
        assertThat(drafted).isEqualTo(new PayoutDraftResult(0, 0));
        then(payoutSellerDraftService).should(never()).saveDraft(any(), any(), any());
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
                List.of(withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L))));
        Payout seller20 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 20L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(2L, 2001L, 20L, 19_400L), 4L))));
        given(payoutRepository.findBySnapshotIdAndSystemFalse(SNAPSHOT_ID)).willReturn(List.of(seller10, seller20));

        // when
        int published = sut.publish(SNAPSHOT_ID);

        // then: payoutId·createdAt은 저장 후 채워지는 값이라 단위 테스트에서는 확인하지 않음
        assertThat(published).isEqualTo(2);
        then(payoutEventPublisher).should(times(2)).publishSellerPayoutCreated(commandCaptor.capture());
        assertThat(commandCaptor.getAllValues())
                .extracting(SellerPayoutCreatedCommand::payeeId, SellerPayoutCreatedCommand::amount)
                .containsExactlyInAnyOrder(
                        tuple(10L, 9_700L),
                        tuple(20L, 19_400L));
    }

    @Test
    void 금액이_0원_이하인_판매자_정산은_지갑_이벤트를_발행하지_않는다() {
        // given: 판매자 10은 9,700원, 판매자 20은 0원(0원 상품만 판매), 판매자 30은 -2,000원(차감 조정만 남음)
        Payout seller10 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 10L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L))));
        Payout zeroSeller20 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 20L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(2L, 2001L, 20L, 0L), 4L))));
        Payout negativeSeller30 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 30L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(3L, 3001L, 30L, -2_000L), 7L))));
        given(payoutRepository.findBySnapshotIdAndSystemFalse(SNAPSHOT_ID))
                .willReturn(List.of(seller10, zeroSeller20, negativeSeller30));

        // when
        int published = sut.publish(SNAPSHOT_ID);

        // then
        assertThat(published).isEqualTo(1);
        then(payoutEventPublisher).should().publishSellerPayoutCreated(commandCaptor.capture());
        assertThat(commandCaptor.getValue().payeeId()).isEqualTo(10L);
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

    // ---------- 막아 둔 API: 1건 수동 실행, 수동 조정 ----------

    // 관리자가 아니면 거절하는 확인은 컨트롤러 @PreAuthorize로 옮겨서 서비스 테스트에는 없음 (PayoutBlockedApiIntegrationTest에서 확인)
    @Test
    void 정산_1건_수동_실행은_아직_지원하지_않는_기능으로_거절한다() {
        // when & then
        assertThatThrownBy(() -> sut.runOrderItem(900L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.FEATURE_NOT_SUPPORTED));
    }

    // 관리자가 아니면 거절하는 확인은 컨트롤러 @PreAuthorize로 옮겨서 서비스 테스트에는 없음 (PayoutBlockedApiIntegrationTest에서 확인)
    @Test
    void 수동_조정은_아직_지원하지_않는_기능으로_거절한다() {
        // when & then
        assertThatThrownBy(() -> sut.createAdjustment(1L, adjustmentCommand()))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.FEATURE_NOT_SUPPORTED));
    }

    // ---------- 정산 목록 조회 ----------

    @Test
    void 판매자_목록은_본인_ID와_판매자_몫으로_고정해_조회한다() {
        // given: 리포지토리가 payeeId = 7, system = false, 0페이지 20건 조건으로 불려야만 결과를 돌려줌 (다른 조건이면 Mockito가 실패시킴)
        PayoutSellerSearchQuery query = sellerSearchQuery();
        given(payoutQueryRepository.searchPayouts(7L, false, query.from(), query.to(), PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(sellerPayout(12L, 7L, 13_000L))));

        // when
        Page<PayoutSummaryResult> result = sut.searchForSeller(7L, query);

        // then
        assertThat(result.getContent())
                .extracting(PayoutSummaryResult::payoutId, PayoutSummaryResult::payeeId, PayoutSummaryResult::amount)
                .containsExactly(tuple(12L, 7L, 13_000L));
    }

    @Test
    void 관리자는_payeeId를_생략하면_조건_없이_전체를_조회한다() {
        // given
        PayoutSearchQuery query = searchQuery(null, null);
        given(payoutQueryRepository.searchPayouts(null, null, query.from(), query.to(), PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(sellerPayout(12L, 7L, 13_000L), systemPayout(14L))));

        // when
        Page<PayoutSummaryResult> result = sut.searchForAdmin(query);

        // then
        assertThat(result.getContent()).extracting(PayoutSummaryResult::payoutId).containsExactly(12L, 14L);
    }

    // ---------- 정산 상세 조회 ----------

    @Test
    void 정산이_없으면_상세_조회를_정산_없음으로_거절한다() {
        // given
        given(payoutRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getDetailForAdmin(99L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_NOT_FOUND));
    }

    @Test
    void 판매자가_다른_판매자의_정산을_상세_조회하면_거절한다() {
        // given: 판매자 7이 판매자 9의 정산 13을 요청
        given(payoutRepository.findById(13L)).willReturn(Optional.of(sellerPayout(13L, 9L, 19_400L)));

        // when & then
        assertThatThrownBy(() -> sut.getDetailForSeller(7L, 13L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_시스템_몫_정산을_상세_조회하면_거절한다() {
        // given: 판매자 7이 시스템 몫 정산 14를 요청 (모든 판매자 수수료가 섞여 있음)
        given(payoutRepository.findById(14L)).willReturn(Optional.of(systemPayout(14L)));

        // when & then
        assertThatThrownBy(() -> sut.getDetailForSeller(7L, 14L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 정산의_스냅샷이_없으면_데이터_손상으로_보고_표준_예외를_던진다() {
        // given: 외래키 때문에 정상이면 생길 수 없는 데이터 손상 상황
        given(payoutRepository.findById(12L)).willReturn(Optional.of(sellerPayout(12L, 7L, 13_000L)));
        given(payoutSnapshotRepository.findById(SNAPSHOT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getDetailForAdmin(12L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산의 스냅샷이 없습니다. payoutId=12");
    }

    @Test
    void 관리자가_판매자_정산을_보면_그_판매자_수수료와_시스템_몫_정산_ID를_함께_준다() {
        // given: 판매자 7의 정산 12, 같은 스냅샷의 시스템 몫 정산 14에 판매자 7 수수료 1건 300원
        givenDetail(sellerPayout(12L, 7L, 13_000L), 2L);
        given(payoutRepository.findBySnapshotIdAndSystemTrue(SNAPSHOT_ID)).willReturn(Optional.of(systemPayout(14L)));
        given(payoutQueryRepository.summarizeSellerFeeItems(14L, 7L)).willReturn(new CountAndAmount(1, 300));

        // when
        PayoutDetailResult result = sut.getDetailForAdmin(12L);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.payoutId()).isEqualTo(12L);
            softly.assertThat(result.payeeId()).isEqualTo(7L);
            softly.assertThat(result.amount()).isEqualTo(13_000L);
            softly.assertThat(result.periodFrom()).isEqualTo(PERIOD_FROM);
            softly.assertThat(result.periodTo()).isEqualTo(PERIOD_TO);
            softly.assertThat(result.itemCount()).isEqualTo(2L);
            softly.assertThat(result.systemPayoutId()).isEqualTo(14L);
            softly.assertThat(result.sellerFeeAmount()).isEqualTo(300L);
            softly.assertThat(result.sellerFeeItemCount()).isEqualTo(1L);
        });
    }

    @Test
    void 판매자가_본인_정산을_보면_수수료를_함께_준다() {
        // given
        givenDetail(sellerPayout(12L, 7L, 13_000L), 2L);
        given(payoutRepository.findBySnapshotIdAndSystemTrue(SNAPSHOT_ID)).willReturn(Optional.of(systemPayout(14L)));
        given(payoutQueryRepository.summarizeSellerFeeItems(14L, 7L)).willReturn(new CountAndAmount(1, 300));

        // when
        PayoutDetailResult result = sut.getDetailForSeller(7L, 12L);

        // then: 시스템 몫 정산 ID는 결과에는 담기고 판매자 응답 DTO에서 빠짐 (PayoutQueryIntegrationTest에서 응답으로 확인)
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.sellerFeeAmount()).isEqualTo(300L);
            softly.assertThat(result.sellerFeeItemCount()).isEqualTo(1L);
        });
    }

    @Test
    void 시스템_몫_정산_상세에는_판매자_수수료_필드가_없다() {
        // given: 시스템 몫 정산 자체가 수수료 묶음이라 따로 조회하지 않음
        givenDetail(systemPayout(14L), 1L);

        // when
        PayoutDetailResult result = sut.getDetailForAdmin(14L);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.system()).isTrue();
            softly.assertThat(result.systemPayoutId()).isNull();
            softly.assertThat(result.sellerFeeAmount()).isNull();
            softly.assertThat(result.sellerFeeItemCount()).isNull();
        });
    }

    @Test
    void 같은_스냅샷에_시스템_몫_정산이_없으면_수수료_필드가_없다() {
        // given: 이번 회차에 수수료가 하나도 없어서 시스템 몫 정산이 만들어지지 않음
        givenDetail(sellerPayout(12L, 7L, 13_000L), 2L);
        given(payoutRepository.findBySnapshotIdAndSystemTrue(SNAPSHOT_ID)).willReturn(Optional.empty());

        // when
        PayoutDetailResult result = sut.getDetailForAdmin(12L);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.systemPayoutId()).isNull();
            softly.assertThat(result.sellerFeeAmount()).isNull();
            softly.assertThat(result.sellerFeeItemCount()).isNull();
        });
    }

    // ---------- 정산 항목 조회 ----------

    @Test
    void 정산이_없으면_항목_조회를_정산_없음으로_거절한다() {
        // given
        given(payoutRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getItemsForAdmin(99L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_NOT_FOUND));
    }

    @Test
    void 판매자가_다른_판매자의_정산_항목을_조회하면_거절한다() {
        // given: 판매자 7이 판매자 9의 정산 13의 항목을 요청
        given(payoutRepository.findById(13L)).willReturn(Optional.of(sellerPayout(13L, 9L, 19_400L)));

        // when & then
        assertThatThrownBy(() -> sut.getItemsForSeller(7L, 13L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_시스템_몫_정산_항목을_조회하면_거절한다() {
        // given: 판매자 7이 시스템 몫 정산 14의 항목을 요청 (모든 판매자 수수료가 섞여 있음)
        given(payoutRepository.findById(14L)).willReturn(Optional.of(systemPayout(14L)));

        // when & then
        assertThatThrownBy(() -> sut.getItemsForSeller(7L, 14L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    // ---------- 도우미 ----------

    // verify가 비교할 집계값을 한 번에 준비 (스냅샷, 판매자 항목, 시스템 항목, 판매자 헤더 금액 합계)
    // 실패 기록은 0건으로 둠: 실패가 섞인 경우의 판정은 PayoutDraftValidatorTest, 실제 흐름은 통합 테스트에서 확인
    private void givenSummaries(CountAndAmount snapshot, CountAndAmount sellerItems,
                                CountAndAmount systemItems, long sellerHeaderAmount) {
        given(payoutQueryRepository.summarizeSnapshotItems(SNAPSHOT_ID)).willReturn(snapshot);
        given(payoutQueryRepository.summarizeSellerDraftItems(SNAPSHOT_ID)).willReturn(sellerItems);
        given(payoutQueryRepository.summarizeSystemDraftItems(SNAPSHOT_ID)).willReturn(systemItems);
        given(payoutQueryRepository.sumSellerDraftAmount(SNAPSHOT_ID)).willReturn(sellerHeaderAmount);
        given(payoutQueryRepository.summarizeFailures(SNAPSHOT_ID)).willReturn(new CountAndAmount(0, 0));
    }

    // 저장된 후보처럼 id를 채움 (setId가 없고 id는 DB 저장 시 채워지므로 단위 테스트에서 직접 넣음)
    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }

    // 목록 조회 조건 (기간은 필수라 10월 한 달로 고정, 0페이지 20건)
    private PayoutSearchQuery searchQuery(Long payeeId, Boolean system) {
        return new PayoutSearchQuery(payeeId, system,
                LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0), 0, 20);
    }

    // 판매자 목록 조회 조건 (기간은 필수라 10월 한 달로 고정, 0페이지 20건)
    private PayoutSellerSearchQuery sellerSearchQuery() {
        return new PayoutSellerSearchQuery(LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0), 0, 20);
    }

    private PayoutAdjustmentCreateCommand adjustmentCommand() {
        return new PayoutAdjustmentCreateCommand(900L, "SALE_AMOUNT", -2_000L, "금액 오입력 정정");
    }

    // 상세 조회의 공통 준비: 정산 조회, 스냅샷(대상 기간), 항목 수
    private void givenDetail(Payout payout, long itemCount) {
        given(payoutRepository.findById(payout.getId())).willReturn(Optional.of(payout));
        given(payoutSnapshotRepository.findById(SNAPSHOT_ID))
                .willReturn(Optional.of(PayoutSnapshot.create(1L, PERIOD_FROM, PERIOD_TO)));
        given(payoutQueryRepository.countItemsByPayoutId(payout.getId())).willReturn(itemCount);
    }

    // 판매자 몫 정산 1건 (판매 대금 항목 1개). DB 없이 만들어서 ID는 직접 넣음
    private Payout sellerPayout(Long payoutId, Long sellerId, long amount) {
        Payout payout = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, sellerId,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(900L + payoutId, 9_000L + payoutId, sellerId, amount), 100L + payoutId))));
        ReflectionTestUtils.setField(payout, "id", payoutId);
        return payout;
    }

    // 시스템 몫 정산 1건 (판매자 7의 수수료 300원 항목 1개)
    private Payout systemPayout(Long payoutId) {
        Payout payout = Payout.forSystem(SNAPSHOT_ID, List.of(PayoutSystemDraftItem.create(SNAPSHOT_ID,
                withId(PayoutCandidateItem.ofSaleFee(910L, 9_100L, 7L, 300L), 200L))));
        ReflectionTestUtils.setField(payout, "id", payoutId);
        return payout;
    }
}
