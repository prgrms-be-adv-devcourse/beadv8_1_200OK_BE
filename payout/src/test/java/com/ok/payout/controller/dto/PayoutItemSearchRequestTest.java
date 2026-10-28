package com.ok.payout.controller.dto;

import com.ok.payout.service.dto.PayoutItemSearchQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutItemSearchRequestTest {

    @Test
    void 페이지_번호와_크기를_생략하면_0과_20으로_채운다() {
        // when
        PayoutItemSearchRequest sut = new PayoutItemSearchRequest(null, null);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.page()).isEqualTo(0);
            softly.assertThat(sut.size()).isEqualTo(20);
        });
    }

    @Test
    void 페이지_조건을_조회_Query로_그대로_옮긴다() {
        // given: page와 size에 일부러 다른 숫자(2, 30)를 넣음. 같은 숫자면 둘이 바뀌어 옮겨져도 티가 안 나서 실수를 못 잡음
        PayoutItemSearchRequest request = new PayoutItemSearchRequest(2, 30);

        // when
        PayoutItemSearchQuery sut = request.toQuery();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.page()).isEqualTo(2);
            softly.assertThat(sut.size()).isEqualTo(30);
        });
    }
}
