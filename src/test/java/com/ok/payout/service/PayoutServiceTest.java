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
import com.ok.payout.service.dto.PayoutItemSearchQuery;
import com.ok.payout.service.dto.PayoutSearchQuery;
import com.ok.payout.service.dto.PayoutSummaryResult;
import com.ok.payout.service.dto.SellerPayoutCreatedCommand;
import com.ok.payout.service.publisher.PayoutEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
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
    // 상세 조회 테스트의 스냅샷 대상 기간
    private static final LocalDateTime PERIOD_FROM = LocalDateTime.of(2026, 9, 27, 0, 0);
    private static final LocalDateTime PERIOD_TO = LocalDateTime.of(2026, 9, 30, 0, 0);
    // 조회 API가 받는 토큰 권한 목록 (실제 토큰 모양: 판매자는 BUYER와 SELLER를 함께 가짐)
    private static final List<String> ADMIN_ROLES = List.of("ADMIN");
    private static final List<String> SELLER_ROLES = List.of("BUYER", "SELLER");
    private static final List<String> BUYER_ROLES = List.of("BUYER");

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
    private PayoutEventPublisher payoutEventPublisher;

    @InjectMocks
    private PayoutService sut;

    @Captor
    private ArgumentCaptor<List<PayoutSellerDraft>> sellerDraftsCaptor;

    @Captor
    private ArgumentCaptor<List<PayoutSystemDraftItem>> systemItemsCaptor;

    @Captor
    private ArgumentCaptor<SellerPayoutCreatedCommand> commandCaptor;

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
                List.of(withId(PayoutCandidateItem.ofSaleAmount(1001L, 10L, 9_700L), 1L))));
        Payout zeroSeller20 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 20L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(2001L, 20L, 0L), 4L))));
        Payout negativeSeller30 = Payout.forSeller(SNAPSHOT_ID, PayoutSellerDraft.create(SNAPSHOT_ID, 30L,
                List.of(withId(PayoutCandidateItem.ofSaleAmount(3001L, 30L, -2_000L), 7L))));
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

    @ParameterizedTest
    @MethodSource("관리자가_아닌_역할")
    void 관리자가_아니면_1건_수동_실행을_권한_없음으로_거절한다(String role) {
        // when & then
        assertThatThrownBy(() -> sut.runOrderItem(role, 900L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    static Stream<String> 관리자가_아닌_역할() {
        return Stream.of("SELLER", "BUYER", "admin", null);
    }

    @Test
    void 관리자여도_1건_수동_실행은_아직_지원하지_않는_기능으로_거절한다() {
        // when & then
        assertThatThrownBy(() -> sut.runOrderItem("ADMIN", 900L))
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
    void 판매자가_payeeId를_생략하면_목록_조회를_거절한다() {
        // given: 판매자 7이 누구의 정산인지 밝히지 않고 요청
        PayoutSearchQuery query = searchQuery(null, null);

        // when & then
        assertThatThrownBy(() -> sut.search(7L, SELLER_ROLES, query))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_다른_판매자_ID로_목록을_조회하면_거절한다() {
        // given: 판매자 7이 판매자 9의 정산을 요청
        PayoutSearchQuery query = searchQuery(9L, null);

        // when & then
        assertThatThrownBy(() -> sut.search(7L, SELLER_ROLES, query))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_본인_ID여도_시스템_몫을_요청하면_거절한다() {
        // given: 판매자 7이 본인 ID로 system = true 요청 (시스템 몫은 모든 판매자 수수료가 섞여 있음)
        PayoutSearchQuery query = searchQuery(7L, true);

        // when & then
        assertThatThrownBy(() -> sut.search(7L, SELLER_ROLES, query))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 관리자_판매자가_아니면_목록_조회를_거절한다() {
        // given: 구매자 7이 본인 ID로 요청해도 정산을 볼 권한이 없음
        PayoutSearchQuery query = searchQuery(7L, null);

        // when & then
        assertThatThrownBy(() -> sut.search(7L, BUYER_ROLES, query))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_본인_ID로_요청하면_그_조건_그대로_조회한다() {
        // given: 리포지토리가 payeeId = 7, 0페이지 20건 조건으로 불려야만 결과를 돌려줌 (다른 조건이면 Mockito가 실패시킴)
        PayoutSearchQuery query = searchQuery(7L, null);
        given(payoutQueryRepository.searchPayouts(7L, null, query.from(), query.to(), PageRequest.of(0, 20)))
                .willReturn(new PageImpl<>(List.of(sellerPayout(12L, 7L, 13_000L))));

        // when
        Page<PayoutSummaryResult> result = sut.search(7L, SELLER_ROLES, query);

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
        Page<PayoutSummaryResult> result = sut.search(1L, ADMIN_ROLES, query);

        // then
        assertThat(result.getContent()).extracting(PayoutSummaryResult::payoutId).containsExactly(12L, 14L);
    }

    // ---------- 정산 상세 조회 ----------

    @Test
    void 정산이_없으면_상세_조회를_정산_없음으로_거절한다() {
        // given
        given(payoutRepository.findById(99L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getDetail(1L, ADMIN_ROLES, 99L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_NOT_FOUND));
    }

    @Test
    void 판매자가_다른_판매자의_정산을_상세_조회하면_거절한다() {
        // given: 판매자 7이 판매자 9의 정산 13을 요청
        given(payoutRepository.findById(13L)).willReturn(Optional.of(sellerPayout(13L, 9L, 19_400L)));

        // when & then
        assertThatThrownBy(() -> sut.getDetail(7L, SELLER_ROLES, 13L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_시스템_몫_정산을_상세_조회하면_거절한다() {
        // given: 판매자 7이 시스템 몫 정산 14를 요청 (모든 판매자 수수료가 섞여 있음)
        given(payoutRepository.findById(14L)).willReturn(Optional.of(systemPayout(14L)));

        // when & then
        assertThatThrownBy(() -> sut.getDetail(7L, SELLER_ROLES, 14L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 관리자_판매자가_아니면_본인_정산이어도_상세_조회를_거절한다() {
        // given: 구매자 역할로 회원 7의 정산 12를 요청
        given(payoutRepository.findById(12L)).willReturn(Optional.of(sellerPayout(12L, 7L, 13_000L)));

        // when & then
        assertThatThrownBy(() -> sut.getDetail(7L, BUYER_ROLES, 12L))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 정산의_스냅샷이_없으면_데이터_손상으로_보고_표준_예외를_던진다() {
        // given: 외래키 때문에 정상이면 생길 수 없는 데이터 손상 상황
        given(payoutRepository.findById(12L)).willReturn(Optional.of(sellerPayout(12L, 7L, 13_000L)));
        given(payoutSnapshotRepository.findById(SNAPSHOT_ID)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> sut.getDetail(1L, ADMIN_ROLES, 12L))
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
        PayoutDetailResult result = sut.getDetail(1L, ADMIN_ROLES, 12L);

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
    void 판매자가_본인_정산을_보면_수수료는_주고_시스템_몫_정산_ID는_주지_않는다() {
        // given
        givenDetail(sellerPayout(12L, 7L, 13_000L), 2L);
        given(payoutRepository.findBySnapshotIdAndSystemTrue(SNAPSHOT_ID)).willReturn(Optional.of(systemPayout(14L)));
        given(payoutQueryRepository.summarizeSellerFeeItems(14L, 7L)).willReturn(new CountAndAmount(1, 300));

        // when
        PayoutDetailResult result = sut.getDetail(7L, SELLER_ROLES, 12L);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.systemPayoutId()).isNull();
            softly.assertThat(result.sellerFeeAmount()).isEqualTo(300L);
            softly.assertThat(result.sellerFeeItemCount()).isEqualTo(1L);
        });
    }

    @Test
    void 시스템_몫_정산_상세에는_판매자_수수료_필드가_없다() {
        // given: 시스템 몫 정산 자체가 수수료 묶음이라 따로 조회하지 않음
        givenDetail(systemPayout(14L), 1L);

        // when
        PayoutDetailResult result = sut.getDetail(1L, ADMIN_ROLES, 14L);

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
        PayoutDetailResult result = sut.getDetail(1L, ADMIN_ROLES, 12L);

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
        assertThatThrownBy(() -> sut.getItems(1L, ADMIN_ROLES, 99L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_NOT_FOUND));
    }

    @Test
    void 판매자가_다른_판매자의_정산_항목을_조회하면_거절한다() {
        // given: 판매자 7이 판매자 9의 정산 13의 항목을 요청
        given(payoutRepository.findById(13L)).willReturn(Optional.of(sellerPayout(13L, 9L, 19_400L)));

        // when & then
        assertThatThrownBy(() -> sut.getItems(7L, SELLER_ROLES, 13L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 판매자가_시스템_몫_정산_항목을_조회하면_거절한다() {
        // given: 판매자 7이 시스템 몫 정산 14의 항목을 요청 (모든 판매자 수수료가 섞여 있음)
        given(payoutRepository.findById(14L)).willReturn(Optional.of(systemPayout(14L)));

        // when & then
        assertThatThrownBy(() -> sut.getItems(7L, SELLER_ROLES, 14L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
    }

    @Test
    void 관리자_판매자가_아니면_본인_정산이어도_항목_조회를_거절한다() {
        // given: 구매자 역할로 회원 7의 정산 12의 항목을 요청
        given(payoutRepository.findById(12L)).willReturn(Optional.of(sellerPayout(12L, 7L, 13_000L)));

        // when & then
        assertThatThrownBy(() -> sut.getItems(7L, BUYER_ROLES, 12L, new PayoutItemSearchQuery(0, 20)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.PAYOUT_ACCESS_DENIED));
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

    // 목록 조회 조건 (기간은 필수라 10월 한 달로 고정, 0페이지 20건)
    private PayoutSearchQuery searchQuery(Long payeeId, Boolean system) {
        return new PayoutSearchQuery(payeeId, system,
                LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0), 0, 20);
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
                List.of(withId(PayoutCandidateItem.ofSaleAmount(9_000L + payoutId, sellerId, amount), 100L + payoutId))));
        ReflectionTestUtils.setField(payout, "id", payoutId);
        return payout;
    }

    // 시스템 몫 정산 1건 (판매자 7의 수수료 300원 항목 1개)
    private Payout systemPayout(Long payoutId) {
        Payout payout = Payout.forSystem(SNAPSHOT_ID, List.of(PayoutSystemDraftItem.create(SNAPSHOT_ID,
                withId(PayoutCandidateItem.ofSaleFee(9_100L, 7L, 300L), 200L))));
        ReflectionTestUtils.setField(payout, "id", payoutId);
        return payout;
    }
}
