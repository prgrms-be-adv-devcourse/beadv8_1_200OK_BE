package com.ok.payout.service;

import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutFailure;
import com.ok.payout.domain.PayoutFailureReason;
import com.ok.payout.domain.PayoutSellerDraft;
import com.ok.payout.domain.PayoutSystemDraftItem;
import com.ok.payout.repository.PayoutFailureRepository;
import com.ok.payout.repository.PayoutSellerDraftRepository;
import com.ok.payout.repository.PayoutSystemDraftItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 단위 테스트: 판매자 1명 저장의 분류(판매자 몫·수수료)와 실패 기록 내용만 확인
// 판매자마다 트랜잭션이 따로 도는지(한 판매자 실패가 다른 판매자에 번지지 않는지)는 실제 트랜잭션이 필요해 PayoutSettleIntegrationTest에서 확인
@ExtendWith(MockitoExtension.class)
class PayoutSellerDraftServiceTest {

    private static final Long SNAPSHOT_ID = 12L;

    @Mock
    private PayoutSellerDraftRepository payoutSellerDraftRepository;

    @Mock
    private PayoutSystemDraftItemRepository payoutSystemDraftItemRepository;

    @Mock
    private PayoutFailureRepository payoutFailureRepository;

    @InjectMocks
    private PayoutSellerDraftService sut;

    @Captor
    private ArgumentCaptor<List<PayoutSellerDraft>> sellerDraftsCaptor;

    @Captor
    private ArgumentCaptor<List<PayoutSystemDraftItem>> systemItemsCaptor;

    @Captor
    private ArgumentCaptor<List<PayoutFailure>> failuresCaptor;

    // ---------- saveDraft ----------

    @Test
    void 판매_대금과_배송비는_판매자_draft로_수수료는_시스템_draft_항목으로_나눠_저장한다() {
        // given: 판매자 10의 대금·수수료·배송비
        List<PayoutCandidateItem> candidates = List.of(
                withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 2L),
                withId(PayoutCandidateItem.ofSaleShippingFee(1L, 10L, 3_000L), 3L));

        // when
        sut.saveDraft(SNAPSHOT_ID, 10L, candidates);

        // then: 판매자 draft 1건(대금 9,700 + 배송비 3,000), 시스템 항목 1건(수수료 300)
        then(payoutSellerDraftRepository).should().saveAll(sellerDraftsCaptor.capture());
        assertThat(sellerDraftsCaptor.getValue())
                .extracting(PayoutSellerDraft::getSellerId, PayoutSellerDraft::getAmount)
                .containsExactly(tuple(10L, 12_700L));

        then(payoutSystemDraftItemRepository).should().saveAll(systemItemsCaptor.capture());
        assertThat(systemItemsCaptor.getValue())
                .extracting(PayoutSystemDraftItem::getCandidateId, PayoutSystemDraftItem::getAmount)
                .containsExactly(tuple(2L, 300L));
    }

    @Test
    void 수수료_후보만_있는_판매자는_판매자_draft를_만들지_않는다() {
        // given: 판매자 10의 수수료 후보만 (판매 대금은 지난 배치에 이미 정산됨)
        List<PayoutCandidateItem> candidates = List.of(withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 2L));

        // when
        sut.saveDraft(SNAPSHOT_ID, 10L, candidates);

        // then: 0원짜리 판매자 정산이 생기지 않도록 판매자 draft 저장 자체를 하지 않음 (기존 동작과 같음)
        then(payoutSellerDraftRepository).should(never()).saveAll(any());
        then(payoutSystemDraftItemRepository).should().saveAll(systemItemsCaptor.capture());
        assertThat(systemItemsCaptor.getValue()).extracting(PayoutSystemDraftItem::getCandidateId).containsExactly(2L);
    }

    // ---------- recordUnexpectedError ----------

    @Test
    void 실패한_판매자의_후보_전체를_예외_종류와_메시지를_담아_UNEXPECTED_ERROR로_기록한다() {
        // given: 판매자 10의 대금·수수료
        List<PayoutCandidateItem> candidates = List.of(
                withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L),
                withId(PayoutCandidateItem.ofSaleFee(1L, 1001L, 10L, 300L), 2L));

        // when
        sut.recordUnexpectedError(SNAPSHOT_ID, candidates, new IllegalStateException("저장 실패"));

        // then
        then(payoutFailureRepository).should().saveAll(failuresCaptor.capture());
        assertThat(failuresCaptor.getValue())
                .extracting(PayoutFailure::getCandidateId, PayoutFailure::getReason, PayoutFailure::getDetail)
                .containsExactly(
                        tuple(1L, PayoutFailureReason.UNEXPECTED_ERROR, "draft 처리 중 예외: IllegalStateException: 저장 실패"),
                        tuple(2L, PayoutFailureReason.UNEXPECTED_ERROR, "draft 처리 중 예외: IllegalStateException: 저장 실패"));
    }

    @Test
    void 예외_메시지가_없으면_대체_문구로_기록한다() {
        // given: 메시지 없이 던져지는 예외 (NullPointerException 등)
        List<PayoutCandidateItem> candidates = List.of(withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L));

        // when
        sut.recordUnexpectedError(SNAPSHOT_ID, candidates, new NullPointerException());

        // then
        then(payoutFailureRepository).should().saveAll(failuresCaptor.capture());
        assertThat(failuresCaptor.getValue().get(0).getDetail())
                .isEqualTo("draft 처리 중 예외: NullPointerException: 메시지 없음");
    }

    @Test
    void 예외_메시지가_길면_255자로_잘라_기록한다() {
        // given: SQL 문장이 들어간 DB 예외처럼 메시지가 아주 긴 경우
        List<PayoutCandidateItem> candidates = List.of(withId(PayoutCandidateItem.ofSaleAmount(1L, 1001L, 10L, 9_700L), 1L));

        // when
        sut.recordUnexpectedError(SNAPSHOT_ID, candidates, new IllegalStateException("가".repeat(500)));

        // then: 길이 그대로 넘기면 실패 기록 생성에서 예외가 나 Job이 멈추므로 잘라서 기록
        then(payoutFailureRepository).should().saveAll(failuresCaptor.capture());
        assertThat(failuresCaptor.getValue().get(0).getDetail())
                .hasSize(PayoutFailure.MAX_DETAIL_LENGTH)
                .endsWith("...");
    }

    // 저장된 후보처럼 id를 채움 (setId가 없고 id는 DB 저장 시 채워지므로 단위 테스트에서 직접 넣음)
    private PayoutCandidateItem withId(PayoutCandidateItem candidate, Long id) {
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
