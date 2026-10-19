package com.ok.payout.service;

import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import com.ok.payout.service.dto.PurchaseConfirmedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutCandidateServiceTest {

    // 주문 ID와 주문항목 ID는 일부러 다른 값. 같은 값이면 두 필드의 자리가 바뀌어도 티가 안 남
    private static final Long ORDER_ID = 100L;
    private static final Long ORDER_ITEM_ID = 1L;
    private static final Long SELLER_ID = 10L;

    @Mock
    private PayoutCandidateItemRepository repository;

    @InjectMocks
    private PayoutCandidateService sut;

    // saveAll()에 넘어간 리스트를 꺼낼 공간
    @Captor
    private ArgumentCaptor<List<PayoutCandidateItem>> itemsCaptor;

    @Test
    void 배송비가_있으면_대금_수수료_배송비_3건을_저장한다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);
        givenNotProcessedYet();

        // when
        sut.createFromPurchaseConfirmed(command);

        // then
        // saveAll이 불렸는지 확인하면서 넘어간 리스트를 꺼냄
        verify(repository).saveAll(itemsCaptor.capture());
        // 배송비는 판매자 묶음 단위라 주문상품 ID 없이 (주문, 판매자)로만 만들어짐
        assertThat(itemsCaptor.getValue())
                .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getOrderId,
                        PayoutCandidateItem::getOrderItemId, PayoutCandidateItem::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(PayoutEventType.SALE_FEE, ORDER_ID, ORDER_ITEM_ID, 300L),
                        tuple(PayoutEventType.SALE_AMOUNT, ORDER_ID, ORDER_ITEM_ID, 9_700L),
                        tuple(PayoutEventType.SALE_SHIPPING_FEE, ORDER_ID, null, 3_000L)
                );
    }

    @Test
    void 무료배송이면_배송비_후보는_만들지_않는다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 0L);
        givenNotProcessedYet();

        // when
        sut.createFromPurchaseConfirmed(command);

        // then
        verify(repository).saveAll(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .extracting(PayoutCandidateItem::getEventType)
                .containsExactlyInAnyOrder(PayoutEventType.SALE_FEE, PayoutEventType.SALE_AMOUNT);
    }

    @Test
    void 같은_묶음의_배송비가_이미_있으면_배송비만_건너뛰고_대금과_수수료는_저장한다() {
        // given: 같은 (주문, 판매자) 묶음의 다른 상품이 먼저 구매 확정되어 배송비 후보가 이미 있음
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);
        givenNotProcessedYet();
        given(repository.existsByOrderIdAndSellerIdAndEventTypeAndAdjustmentId(
                ORDER_ID, SELLER_ID, PayoutEventType.SALE_SHIPPING_FEE, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID))
                .willReturn(true);

        // when
        sut.createFromPurchaseConfirmed(command);

        // then: 이 상품의 대금·수수료만 저장되고 배송비는 다시 만들지 않음
        verify(repository).saveAll(itemsCaptor.capture());
        assertThat(itemsCaptor.getValue())
                .extracting(PayoutCandidateItem::getEventType)
                .containsExactlyInAnyOrder(PayoutEventType.SALE_FEE, PayoutEventType.SALE_AMOUNT);
    }

    @Test
    void 대금과_수수료의_합은_판매가와_같다() {
        // given
        long totalItemPrice = 10_050L;
        // 배송비는 0원으로 두고 대금·수수료만 계산
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, totalItemPrice, 0L);
        givenNotProcessedYet();

        // when
        sut.createFromPurchaseConfirmed(command);

        // then
        verify(repository).saveAll(itemsCaptor.capture());
        long sum = 0;
        for (PayoutCandidateItem item : itemsCaptor.getValue()) {
            sum += item.getAmount();
        }
        assertThat(sum).isEqualTo(totalItemPrice);
    }

    @Test
    void 관리자_조정이_아닌_평소_이벤트로_이미_처리된_주문항목이면_예외_없이_저장하지_않는다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);
        given(repository.existsByOrderItemIdAndEventTypeInAndAdjustmentId(
                ORDER_ITEM_ID, PayoutEventType.PURCHASE_CONFIRMED_TYPES, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID))
                .willReturn(true);

        // when & then
        // 중복 이벤트는 오류가 아니라 이미 처리된 건 → 예외 없이 조용히 끝나야 함 (멱등)
        assertThatCode(() -> sut.createFromPurchaseConfirmed(command)).doesNotThrowAnyException();
        // 중복이면 저장 시도 자체를 하지 않아야 함
        verify(repository, never()).saveAll(any());
    }

    // ---------- 이벤트 값 검증 ----------

    @Test
    void 주문_ID가_없으면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(null, ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.MISSING_ORDER_ID);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 주문항목_ID가_없으면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, null, SELLER_ID, 10_000L, 3_000L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.MISSING_ORDER_ITEM_ID);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 판매자_ID가_없으면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, null, 10_000L, 3_000L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.MISSING_SELLER_ID);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 상품총액이_없으면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, null, 3_000L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 상품총액이_음수면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, -1L, 3_000L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 배송비가_없으면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, null);

        // when & then
        assertErrorCode(command, PayoutErrorCode.INVALID_SHIPPING_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void 배송비가_음수면_예외가_난다() {
        // given
        PurchaseConfirmedCommand command = new PurchaseConfirmedCommand(ORDER_ID, ORDER_ITEM_ID, SELLER_ID, 10_000L, -1L);

        // when & then
        assertErrorCode(command, PayoutErrorCode.INVALID_SHIPPING_PRICE);
        verify(repository, never()).saveAll(any());
    }

    // 가짜 리포지토리가 "아직 처리 안 된 주문항목"이라고 대답하게 함
    private void givenNotProcessedYet() {
        given(repository.existsByOrderItemIdAndEventTypeInAndAdjustmentId(
                ORDER_ITEM_ID, PayoutEventType.PURCHASE_CONFIRMED_TYPES, PayoutCandidateItem.NO_ADMIN_ADJUSTMENT_ID))
                .willReturn(false);
    }

    // RestApiException이 나고, 그 안의 에러 코드가 기대값인지 확인
    private void assertErrorCode(PurchaseConfirmedCommand command, PayoutErrorCode expected) {
        assertThatThrownBy(() -> sut.createFromPurchaseConfirmed(command))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
