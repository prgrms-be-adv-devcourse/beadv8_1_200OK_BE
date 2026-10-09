package com.ok.payout.service;

import com.ok.common.event.OrderProductConfirmedEvent;
import com.ok.common.exception.RestApiException;
import com.ok.payout.domain.PayoutCandidateItem;
import com.ok.payout.domain.PayoutErrorCode;
import com.ok.payout.domain.PayoutEventType;
import com.ok.payout.repository.PayoutCandidateItemRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 단위 테스트: Spring 없이, 리포지토리는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class PayoutCandidateServiceTest {

    private static final Long ORDER_ITEM_ID = 1L;
    private static final Long SELLER_ID = 10L;

    @Mock
    private PayoutCandidateItemRepository repository;

    @InjectMocks
    private PayoutCandidateService service;

    // saveAll()에 넘어간 리스트를 꺼낼 공간
    @Captor
    private ArgumentCaptor<List<PayoutCandidateItem>> itemsCaptor;

    @Test
    @DisplayName("배송비가 있으면 대금, 수수료, 배송비 3건을 저장한다")
    void savesThreeCandidates_whenShippingFeeExists() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);
        givenNotProcessedYet();

        // Act
        service.createFromPurchaseConfirmed(event);

        // Assert
        verify(repository).saveAll(itemsCaptor.capture());// saveAll이 1번 불렸는지 검증 + 넘어간 리스트 꺼내기

        assertThat(itemsCaptor.getValue())
                .extracting(PayoutCandidateItem::getEventType, PayoutCandidateItem::getAmount)
                .containsExactlyInAnyOrder(
                        tuple(PayoutEventType.SALE_FEE, 300L),
                        tuple(PayoutEventType.SALE_AMOUNT, 9_700L),
                        tuple(PayoutEventType.SALE_SHIPPING_FEE, 3_000L)
                );//실검증 진짜 값 제대로 되었는지
    }

    @Test
    @DisplayName("무료배송이면 배송비 후보는 만들지 않는다")
    void doesNotCreateShippingFeeCandidate_whenFreeShipping() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, 10_000L, 0L);
        givenNotProcessedYet();

        // Act
        service.createFromPurchaseConfirmed(event);

        // Assert
        verify(repository).saveAll(itemsCaptor.capture());

        assertThat(itemsCaptor.getValue())
                .extracting(PayoutCandidateItem::getEventType)
                .containsExactlyInAnyOrder(PayoutEventType.SALE_FEE, PayoutEventType.SALE_AMOUNT);
    }

    @Test
    @DisplayName("대금과 수수료의 합은 판매가와 같다")
    void sumOfSaleAmountAndFeeEqualsTotalItemPrice() {
        // Arrange
        long totalItemPrice = 10_050L;
        //배송비 0원처리로 계산
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, totalItemPrice, 0L);
        givenNotProcessedYet();

        // Act
        service.createFromPurchaseConfirmed(event);

        // Assert
        verify(repository).saveAll(itemsCaptor.capture());

        long sum = itemsCaptor.getValue().stream().mapToLong(PayoutCandidateItem::getAmount).sum();
        assertThat(sum).isEqualTo(totalItemPrice);
    }

    @Test
    @DisplayName("이미 처리된 주문항목이면 예외 없이 저장하지 않는다")
    void skipsWithoutSaving_whenOrderItemAlreadyProcessed() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, 10_000L, 3_000L);
        given(repository.existsByOrderItemIdAndEventTypeIn(ORDER_ITEM_ID, PayoutEventType.PURCHASE_CONFIRMED_TYPES))
                .willReturn(true);

        // Act & Assert
        // 중복 이벤트는 오류가 아니라 이미 처리된 건 → 예외 없이 조용히 끝나야 함 (멱등)
        assertThatCode(() -> service.createFromPurchaseConfirmed(event)).doesNotThrowAnyException();
        verify(repository, never()).saveAll(any());// saveAll이 한 번도 안 불렸는지 확인 (중복이면 저장 시도 자체를 안 해야 함)
    }

    // ---------- 이벤트 값 검증 ----------

    @Test
    @DisplayName("주문항목 ID가 없으면 예외가 난다")
    void throwsException_whenOrderItemIdIsNull() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(null, SELLER_ID, 10_000L, 3_000L);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.MISSING_ORDER_ITEM_ID);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("판매자 ID가 없으면 예외가 난다")
    void throwsException_whenSellerIdIsNull() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, null, 10_000L, 3_000L);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.MISSING_SELLER_ID);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("상품총액이 없으면 예외가 난다")
    void throwsException_whenTotalItemPriceIsNull() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, null, 3_000L);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("상품총액이 음수면 예외가 난다")
    void throwsException_whenTotalItemPriceIsNegative() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, -1L, 3_000L);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.INVALID_TOTAL_ITEM_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("배송비가 없으면 예외가 난다")
    void throwsException_whenShippingPriceIsNull() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, 10_000L, null);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.INVALID_SHIPPING_PRICE);
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("배송비가 음수면 예외가 난다")
    void throwsException_whenShippingPriceIsNegative() {
        // Arrange
        OrderProductConfirmedEvent event = new OrderProductConfirmedEvent(ORDER_ITEM_ID, SELLER_ID, 10_000L, -1L);

        // Act & Assert
        assertErrorCode(event, PayoutErrorCode.INVALID_SHIPPING_PRICE);
        verify(repository, never()).saveAll(any());
    }

    // 가짜 리포지토리가 "아직 처리 안 된 주문항목"이라고 대답하게 함
    private void givenNotProcessedYet() {
        given(repository.existsByOrderItemIdAndEventTypeIn(ORDER_ITEM_ID, PayoutEventType.PURCHASE_CONFIRMED_TYPES))
                .willReturn(false);
    }

    // RestApiException이 나고, 그 안의 에러 코드가 기대값인지 확인
    private void assertErrorCode(OrderProductConfirmedEvent event, PayoutErrorCode expected) {
        RestApiException e = assertThrows(RestApiException.class, () -> service.createFromPurchaseConfirmed(event));
        assertThat(e.getErrorCode()).isEqualTo(expected);
    }
}
