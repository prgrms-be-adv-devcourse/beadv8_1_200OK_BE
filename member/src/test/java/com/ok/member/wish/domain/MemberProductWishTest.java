package com.ok.member.wish.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class MemberProductWishTest {

    private static final LocalDateTime WISHED_AT = LocalDateTime.of(2026, 11, 20, 10, 0);
    private static final LocalDateTime LATER = WISHED_AT.plusHours(1);
    private static final LocalDateTime LATEST = WISHED_AT.plusHours(2);

    @Test
    void 처음_찜하면_찜한_상태로_만든다() {
        // when
        MemberProductWish sut = MemberProductWish.wish(1L, 101L, WISHED_AT);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getMemberId()).isEqualTo(1L);
            softly.assertThat(sut.getProductId()).isEqualTo(101L);
            softly.assertThat(sut.getWishedAt()).isEqualTo(WISHED_AT);
            softly.assertThat(sut.isWished()).isTrue();
        });
    }

    @Test
    void 상품_ID가_없으면_만들지_않는다() {
        // when & then
        assertThatThrownBy(() -> MemberProductWish.wish(1L, null, WISHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("productId는 필수입니다.");
    }

    @Test
    void 이미_찜한_상태에서_다시_찜하면_찜한_시각을_바꾸지_않는다() {
        // given
        MemberProductWish sut = MemberProductWish.wish(1L, 101L, WISHED_AT);

        // when
        sut.rewish(LATER);

        // then
        assertThat(sut.getWishedAt()).isEqualTo(WISHED_AT);
    }

    @Test
    void 해제한_찜을_다시_찜하면_되살리고_찜한_시각을_바꾼다() {
        // given
        MemberProductWish sut = MemberProductWish.wish(1L, 101L, WISHED_AT);
        sut.unwish(LATER);

        // when
        sut.rewish(LATEST);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.isWished()).isTrue();
            softly.assertThat(sut.getDeletedAt()).isNull();
            softly.assertThat(sut.getWishedAt()).isEqualTo(LATEST);
        });
    }

    @Test
    void 이미_해제한_찜을_다시_해제하면_처음_해제한_시각을_그대로_둔다() {
        // given
        MemberProductWish sut = MemberProductWish.wish(1L, 101L, WISHED_AT);
        sut.unwish(LATER);

        // when
        sut.unwish(LATEST);

        // then
        assertThat(sut.getDeletedAt()).isEqualTo(LATER);
    }

}
