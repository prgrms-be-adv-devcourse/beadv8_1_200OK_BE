package com.ok.payout.adapter.in.web.dto;

import com.ok.payout.application.port.in.PayoutResolutionRequestSearchQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

// 기간·페이지 검증은 정산 목록 요청을 복사한 것이라 422 경우는 통합 테스트(PayoutFailureResolutionIntegrationTest)에서 확인함
// 여기서는 기본값과 Query로 옮기는 것만 봄
class PayoutResolutionRequestSearchRequestTest {

    private static final LocalDateTime NOV_16 = LocalDateTime.of(2026, 11, 16, 0, 0);
    private static final LocalDateTime NOV_23 = LocalDateTime.of(2026, 11, 23, 0, 0);

    @Test
    void 생략된_페이지_번호와_크기는_기본값으로_채운다() {
        // when
        PayoutResolutionRequestSearchRequest sut = new PayoutResolutionRequestSearchRequest(NOV_16, NOV_23, null, null, null);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.adminId()).isNull();   // 생략하면 전체 관리자
            softly.assertThat(sut.page()).isEqualTo(0);
            softly.assertThat(sut.size()).isEqualTo(20);
        });
    }

    @Test
    void 조회_조건을_조회_Query로_그대로_옮긴다() {
        // given: from·to는 다른 날짜, adminId·page·size는 서로 다른 숫자 (바뀌어 옮겨져도 티가 나게)
        PayoutResolutionRequestSearchRequest request = new PayoutResolutionRequestSearchRequest(NOV_16, NOV_23, 7L, 2, 30);

        // when
        PayoutResolutionRequestSearchQuery sut = request.toQuery();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.from()).isEqualTo(NOV_16);
            softly.assertThat(sut.to()).isEqualTo(NOV_23);
            softly.assertThat(sut.adminId()).isEqualTo(7L);
            softly.assertThat(sut.page()).isEqualTo(2);
            softly.assertThat(sut.size()).isEqualTo(30);
        });
    }
}
