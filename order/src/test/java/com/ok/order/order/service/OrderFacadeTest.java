package com.ok.order.order.service;

import com.ok.common.exception.RestApiException;
import com.ok.order.cart.service.CartService;
import com.ok.order.cart.service.dto.SelectedCartItem;
import com.ok.order.order.client.DeliveryApiClient;
import com.ok.order.order.client.ProductApiClient;
import com.ok.order.order.client.WalletApiClient;
import com.ok.order.order.client.dto.ProductOptionInfoResponse;
import com.ok.order.order.client.dto.RemoteAreaFeeResponse;
import com.ok.order.order.client.dto.StockAllocationItemRequest;
import com.ok.order.order.client.dto.WalletBalanceResponse;
import com.ok.order.order.domain.Order;
import com.ok.order.order.domain.OrderStatus;
import com.ok.order.order.exception.OrderErrorCode;
import com.ok.order.order.service.dto.CheckoutResult;
import com.ok.order.order.service.dto.CreateOrderCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OrderFacadeTest {

    @Mock CartService cartService;
    @Mock OrderService orderService;
    @Mock ProductApiClient productApiClient;
    @Mock WalletApiClient walletApiClient;
    @Mock DeliveryApiClient deliveryApiClient;

    @InjectMocks OrderFacade sut;

    @Captor ArgumentCaptor<Order> orderCaptor;
    @Captor ArgumentCaptor<List<StockAllocationItemRequest>> stockItemsCaptor;

    static final Long BUYER_ID = 1L;
    static final Long SELLER_ID = 100L;
    static final String ZIP_CODE = "06236";
    static final String IDEMPOTENCY_KEY = "3f2a9c1e-7b4d-4e8a-9c2f-1a2b3c4d5e6f";

    // 상품 모듈 응답 한 건 (옵션 ID, 판매자, 가격, 재고, 구매 가능 여부)
    private ProductOptionInfoResponse option(Long optionId, Long sellerId, long salePrice, int stock, boolean purchasable) {
        return new ProductOptionInfoResponse(optionId * 10, optionId, sellerId,
                "상품" + optionId, "옵션" + optionId, salePrice, stock, purchasable);
    }

    // 판매 중이고 재고가 넉넉한 다른 판매자의 상품
    private ProductOptionInfoResponse onSale(Long optionId, long salePrice) {
        return option(optionId, SELLER_ID, salePrice, 100, true);
    }

    private CreateOrderCommand command(long expectedTotalAmount) {
        return command(IDEMPOTENCY_KEY, expectedTotalAmount);
    }

    private CreateOrderCommand command(String idempotencyKey, long expectedTotalAmount) {
        return new CreateOrderCommand(BUYER_ID, idempotencyKey, "홍길동", "010-1234-5678", ZIP_CODE,
                "서울시 강남구 테헤란로 1", "101호", true, expectedTotalAmount);
    }

    // ---------- 주문서 조회 ----------

    @Test
    void 선택한_장바구니_상품이_없으면_주문서를_만들_수_없다() {
        // given
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.getCheckout(BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.EMPTY_ORDER_ITEMS));
        then(productApiClient).shouldHaveNoInteractions();
    }

    @Test
    void 상품_모듈에_없는_옵션이면_주문할_수_없다() {
        // given: 상품 모듈 응답에서 빠짐 (삭제된 옵션)
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> sut.getCheckout(BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_OPTION_NOT_FOUND));
    }

    @Test
    void 판매_중이_아닌_상품은_주문할_수_없다() {
        // given
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(option(11L, SELLER_ID, 10_000L, 100, false)));

        // when & then
        assertThatThrownBy(() -> sut.getCheckout(BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.PRODUCT_NOT_ON_SALE));
    }

    @Test
    void 담은_수량보다_재고가_적으면_주문할_수_없다() {
        // given: 2개 담았는데 재고 1개
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 2)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(option(11L, SELLER_ID, 10_000L, 1, true)));

        // when & then
        assertThatThrownBy(() -> sut.getCheckout(BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.OUT_OF_STOCK));
    }

    @Test
    void 본인이_판매하는_상품은_주문할_수_없다() {
        // given: 판매자 ID가 주문하는 회원 ID와 같음
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(option(11L, BUYER_ID, 10_000L, 100, true)));

        // when & then
        assertThatThrownBy(() -> sut.getCheckout(BUYER_ID))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.CANNOT_ORDER_OWN_PRODUCT));
    }

    @Test
    void 주문서는_상품_모듈의_최신_가격으로_계산하고_저장하지_않는다() {
        // given: 같은 판매자 상품 2종
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(
                new SelectedCartItem(11L, 2),
                new SelectedCartItem(12L, 1)));
        given(productApiClient.getOptions(List.of(11L, 12L))).willReturn(List.of(
                onSale(11L, 10_000L),
                onSale(12L, 5_000L)));

        // when
        CheckoutResult result = sut.getCheckout(BUYER_ID);

        // then
        assertThat(result).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(result.sellerGroups()).hasSize(1);
            softly.assertThat(result.sellerGroups().get(0).items()).hasSize(2);
            softly.assertThat(result.totalProductAmount()).isEqualTo(25_000L);
            softly.assertThat(result.totalAmount()).isEqualTo(25_000L);
        });
        then(orderService).shouldHaveNoInteractions();
    }

    // ---------- 주문 생성 ----------

    @Test
    void 사용자가_본_금액과_다르면_주문을_저장하지_않는다() {
        // given: 실제 10,000원인데 화면에서는 9,000원을 봄 (그 사이 가격 변경)
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, false, 0L));

        // when & then
        assertThatThrownBy(() -> sut.createOrder(command(9_000L)))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_AMOUNT_CHANGED));
        then(orderService).should(never()).create(any(Order.class));
    }

    @Test
    void 잔액이_부족하면_주문을_저장하지_않는다() {
        // given: 결제 금액 10,000원, 잔액 5,000원
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, false, 0L));
        given(walletApiClient.getBalance(BUYER_ID)).willReturn(new WalletBalanceResponse(BUYER_ID, 5_000L));

        // when & then
        assertThatThrownBy(() -> sut.createOrder(command(10_000L)))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.INSUFFICIENT_BALANCE));
        then(orderService).should(never()).create(any(Order.class));
    }

    @Test
    void 재고_선점에_실패하면_선점을_해제하고_주문을_삭제한다() {
        // given: 주문 저장까지 성공, 재고 선점에서 실패
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, false, 0L));
        given(walletApiClient.getBalance(BUYER_ID)).willReturn(new WalletBalanceResponse(BUYER_ID, 50_000L));
        given(orderService.create(any(Order.class))).willReturn(10L);
        willThrow(new RestApiException(OrderErrorCode.OUT_OF_STOCK))
                .given(productApiClient).allocateStock(eq(10L), anyList());

        // when & then
        assertThatThrownBy(() -> sut.createOrder(command(10_000L)))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.OUT_OF_STOCK));
        then(productApiClient).should().releaseStock(10L);
        then(orderService).should().delete(10L);
        then(orderService).should(never()).publishCreated(anyLong());
    }

    @Test
    void 주문을_저장하고_재고를_선점한_뒤_생성_이벤트를_발행한다() {
        // given: 상품 10,000원 + 도서산간 운임 3,000원 = 13,000원
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, true, 3_000L));
        given(walletApiClient.getBalance(BUYER_ID)).willReturn(new WalletBalanceResponse(BUYER_ID, 50_000L));
        given(orderService.create(any(Order.class))).willReturn(10L);

        // when
        Long orderId = sut.createOrder(command(13_000L));

        // then: 저장한 주문
        then(orderService).should().create(orderCaptor.capture());
        Order saved = orderCaptor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(orderId).isEqualTo(10L);
            softly.assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            softly.assertThat(saved.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
            softly.assertThat(saved.getRecipientName()).isEqualTo("홍길동");
            softly.assertThat(saved.getZipCode()).isEqualTo(ZIP_CODE);
            softly.assertThat(saved.getTotalRemoteAreaFee()).isEqualTo(3_000L);
            softly.assertThat(saved.getTotalAmount()).isEqualTo(13_000L);
            softly.assertThat(saved.getThirdPartyConsents()).hasSize(1);
        });

        // then: 재고 선점 요청과 이벤트 발행
        then(productApiClient).should().allocateStock(eq(10L), stockItemsCaptor.capture());
        assertThat(stockItemsCaptor.getValue()).containsExactly(new StockAllocationItemRequest(11L, 1));
        then(orderService).should().publishCreated(10L);
    }

    // ---------- 주문 생성 멱등키 ----------

    @Test
    void 같은_멱등키로_만든_주문이_있으면_외부_API를_부르지_않고_그_주문을_돌려준다() {
        // given
        given(orderService.findIdByIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.of(10L));

        // when
        Long orderId = sut.createOrder(command(13_000L));

        // then: 다시 만들지 않는 것 자체가 요구사항이라 호출하지 않았는지 확인
        assertThat(orderId).isEqualTo(10L);
        then(cartService).should(never()).getSelectedCartItems(anyLong());
        then(walletApiClient).should(never()).getBalance(anyLong());
        then(orderService).should(never()).create(any(Order.class));
        then(productApiClient).should(never()).allocateStock(anyLong(), anyList());
        then(orderService).should(never()).publishCreated(anyLong());
    }

    @Test
    void 같은_멱등키의_동시_요청이_먼저_저장했으면_재고를_선점하지_않고_먼저_저장된_주문을_돌려준다() {
        // given: 조회 시점에는 없었지만, 저장할 때 다른 요청이 먼저 저장해 유니크 제약에 걸림
        given(orderService.findIdByIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY))
                .willReturn(Optional.empty(), Optional.of(10L));
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, false, 0L));
        given(walletApiClient.getBalance(BUYER_ID)).willReturn(new WalletBalanceResponse(BUYER_ID, 50_000L));
        given(orderService.create(any(Order.class))).willThrow(new DataIntegrityViolationException("uk_orders_buyer_id_idempotency_key"));

        // when
        Long orderId = sut.createOrder(command(10_000L));

        // then: 재고 선점과 이벤트 발행은 먼저 저장한 요청의 몫
        assertThat(orderId).isEqualTo(10L);
        then(productApiClient).should(never()).allocateStock(anyLong(), anyList());
        then(orderService).should(never()).publishCreated(anyLong());
    }

    @Test
    void 멱등키가_아닌_제약에_걸려_저장에_실패하면_예외를_그대로_던진다() {
        // given: 저장은 실패했는데 같은 키의 주문이 없음 = 다른 원인
        given(orderService.findIdByIdempotencyKey(BUYER_ID, IDEMPOTENCY_KEY)).willReturn(Optional.empty());
        given(cartService.getSelectedCartItems(BUYER_ID)).willReturn(List.of(new SelectedCartItem(11L, 1)));
        given(productApiClient.getOptions(List.of(11L))).willReturn(List.of(onSale(11L, 10_000L)));
        given(deliveryApiClient.getRemoteAreaFee(ZIP_CODE)).willReturn(new RemoteAreaFeeResponse(ZIP_CODE, false, 0L));
        given(walletApiClient.getBalance(BUYER_ID)).willReturn(new WalletBalanceResponse(BUYER_ID, 50_000L));
        given(orderService.create(any(Order.class))).willThrow(new DataIntegrityViolationException("other"));

        // when & then
        assertThatThrownBy(() -> sut.createOrder(command(10_000L)))
                .isInstanceOf(DataIntegrityViolationException.class);
        then(productApiClient).should(never()).allocateStock(anyLong(), anyList());
    }

    @ParameterizedTest
    @MethodSource("잘못된_멱등키")
    void 멱등키가_비었거나_64자를_넘으면_주문할_수_없다(String idempotencyKey) {
        // when & then
        assertThatThrownBy(() -> sut.createOrder(command(idempotencyKey, 10_000L)))
                .isInstanceOfSatisfying(RestApiException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_IDEMPOTENCY_KEY));
        then(orderService).should(never()).create(any(Order.class));
    }

    static Stream<String> 잘못된_멱등키() {
        return Stream.of(null, "", "   ", "a".repeat(65));
    }

    // ---------- 결제 실패 ----------

    @Test
    void 결제가_실패하면_선점한_재고를_해제하고_장바구니는_건드리지_않는다() {
        // given
        given(orderService.failPayment(10L)).willReturn(true);

        // when
        sut.failPayment(10L, "INSUFFICIENT_BALANCE");

        // then
        then(productApiClient).should().releaseStock(10L);
        then(cartService).shouldHaveNoInteractions();
    }

    @Test
    void 재고를_풀_필요가_없는_주문이면_해제하지_않는다() {
        // given
        given(orderService.failPayment(10L)).willReturn(false);

        // when
        sut.failPayment(10L, "INSUFFICIENT_BALANCE");

        // then
        then(productApiClient).should(never()).releaseStock(anyLong());
    }
}