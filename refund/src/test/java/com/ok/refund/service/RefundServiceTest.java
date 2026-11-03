package com.ok.refund.service;

import com.ok.common.exception.RestApiException;
import com.ok.refund.domain.Refund;
import com.ok.refund.exception.RefundErrorCode;
import com.ok.refund.repository.RefundRepository;
import com.ok.refund.service.dto.RefundRequestCommand;
import com.ok.refund.service.dto.RefundableOrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long BUYER_ID = 1L;
    private static final Long OTHER_BUYER_ID = 999L;

    @Mock
    RefundRepository refundRepository;

    RefundService sut;

    @BeforeEach
    void setUp() {
        sut = new RefundService(refundRepository);
    }

    @Test
    void 요청하면_주문에서_받은_값으로_요청됨을_저장한다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        sut.request(command(2), socks("PREPARING", 3));

        // then
        ArgumentCaptor<Refund> captor = ArgumentCaptor.forClass(Refund.class);
        then(refundRepository).should().saveAndFlush(captor.capture());
        Refund saved = captor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getQuantity()).isEqualTo(2);
            softly.assertThat(saved.getSellerId()).isEqualTo(5L);
            softly.assertThat(saved.getProductOptionId()).isEqualTo(55L);
            softly.assertThat(saved.getUnitPrice()).isEqualTo(10_000L);
            softly.assertThat(saved.getItemAmount()).isEqualTo(20_000L);
            softly.assertThat(saved.getCancelReason()).isEqualTo("옵션을 잘못 골랐습니다.");
        });
    }

    @Test
    void 남의_주문이면_없는_주문처럼_막는다() {
        // given
        RefundRequestCommand otherBuyer = new RefundRequestCommand(OTHER_BUYER_ID, 10L, 101L, 1, null);

        // when & then
        assertThatThrownBy(() -> sut.request(otherBuyer, socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_FOUND));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 출고_전이_아니면_막는다() {
        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("SHIPPING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.ORDER_ITEM_NOT_CANCELLABLE));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 산_개수보다_많으면_막는다() {
        // when & then
        assertThatThrownBy(() -> sut.request(command(4), socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_QUANTITY_EXCEEDED));
        then(refundRepository).should(never()).saveAndFlush(any());
    }

    @Test
    void 산_개수만큼은_요청할_수_있다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willAnswer(invocation -> invocation.getArgument(0));

        // when
        sut.request(command(3), socks("PREPARING", 3));

        // then
        then(refundRepository).should().saveAndFlush(any(Refund.class));
    }

    @Test
    void 진행_중_UNIQUE에_걸리면_진행_중_에러로_바꾼다() {
        // given
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(new DataIntegrityViolationException("중복",
                new SQLException("Duplicate entry '101' for key 'refund.uk_refund_active_order_item_id'")));

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(RefundErrorCode.REFUND_ALREADY_IN_PROGRESS));
    }

    @Test
    void 다른_무결성_오류는_그대로_던진다() {
        // given
        DataIntegrityViolationException other = new DataIntegrityViolationException("다른 제약",
                new SQLException("Check constraint 'ck_refund_quantity' is violated."));
        given(refundRepository.saveAndFlush(any(Refund.class))).willThrow(other);

        // when & then
        assertThatThrownBy(() -> sut.request(command(1), socks("PREPARING", 3)))
                .isSameAs(other);
    }

    private static RefundRequestCommand command(int quantity) {
        return new RefundRequestCommand(BUYER_ID, 10L, 101L, quantity, "옵션을 잘못 골랐습니다.");
    }

    private static RefundableOrderItem socks(String status, int boughtQuantity) {
        return new RefundableOrderItem(10L, 101L, BUYER_ID, 5L, 55L, "양말", "흰색", status, 10_000L, boughtQuantity);
    }
}
