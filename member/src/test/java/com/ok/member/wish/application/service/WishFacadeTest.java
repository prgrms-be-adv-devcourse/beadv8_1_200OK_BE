package com.ok.member.wish.application.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class WishFacadeTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long PRODUCT_ID = 101L;

    @Mock
    WishService wishService;

    WishFacade wishFacade;

    @BeforeEach
    void setUp() {
        wishFacade = new WishFacade(wishService);
    }

    @Test
    void 동시_등록과_겹쳐_유니크_제약에_걸리면_한_번_다시_시도해_정상으로_끝난다() {
        // given
        willThrow(new DataIntegrityViolationException("uk_member_product_wish_member_id_product_id"))
                .willDoNothing()
                .given(wishService).wish(MEMBER_ID, PRODUCT_ID);

        // when & then
        assertThatCode(() -> wishFacade.wish(MEMBER_ID, PRODUCT_ID)).doesNotThrowAnyException();
        then(wishService).should(times(2)).wish(MEMBER_ID, PRODUCT_ID);
    }

    @Test
    void 다시_시도해도_실패하면_더_시도하지_않고_예외를_그대로_던진다() {
        // given
        willThrow(new DataIntegrityViolationException("uk_member_product_wish_member_id_product_id"))
                .given(wishService).wish(MEMBER_ID, PRODUCT_ID);

        // when & then
        assertThatThrownBy(() -> wishFacade.wish(MEMBER_ID, PRODUCT_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("uk_member_product_wish_member_id_product_id");
        then(wishService).should(times(2)).wish(MEMBER_ID, PRODUCT_ID);
    }

    @Test
    void 제약_위반이_아닌_예외는_다시_시도하지_않는다() {
        // given
        willThrow(new IllegalArgumentException("productId는 필수입니다."))
                .given(wishService).wish(MEMBER_ID, null);

        // when & then
        assertThatThrownBy(() -> wishFacade.wish(MEMBER_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("productId는 필수입니다.");
        then(wishService).should(times(1)).wish(MEMBER_ID, null);
    }

    @Test
    void 처음_시도가_성공하면_다시_시도하지_않는다() {
        // given
        willDoNothing().given(wishService).wish(MEMBER_ID, PRODUCT_ID);

        // when
        wishFacade.wish(MEMBER_ID, PRODUCT_ID);

        // then
        then(wishService).should(times(1)).wish(MEMBER_ID, PRODUCT_ID);
    }

}
