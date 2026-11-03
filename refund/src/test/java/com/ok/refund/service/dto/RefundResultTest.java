package com.ok.refund.service.dto;

import com.ok.refund.domain.Refund;
import com.ok.refund.domain.RefundShippingFee;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static com.ok.refund.fixture.RefundFixture.NOW;
import static com.ok.refund.fixture.RefundFixture.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class RefundResultTest {

    @ParameterizedTest
    @MethodSource("승인_때_저장한_배송비")
    void 승인_불가로_끝나면_승인_때_저장한_배송비_칸도_0으로_보인다(RefundShippingFee fee) {
        // given: 승인 때 배송비 금액을 저장한 뒤 배송 잠금이 거절되어 승인 불가로 끝남
        Refund refund = requested(1);
        refund.startProcessing(fee, NOW);
        refund.markNotApprovable(NOW);

        // when
        RefundResult sut = RefundResult.from(refund);

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.shippingFeeAmount()).isZero();
            softly.assertThat(sut.remoteAreaFeeAmount()).isZero();
            softly.assertThat(sut.deductedShippingFeeAmount()).isZero();
            softly.assertThat(sut.refundAmount()).isZero();
            softly.assertThat(sut.itemAmount()).isEqualTo(10_000L);
        });
    }

    // 돌려줄 배송비(묶음 전부 취소)와 뺄 배송비(무료배송이 깨진 일부 취소)는 함께 저장되지 않아 따로 둔다
    static Stream<RefundShippingFee> 승인_때_저장한_배송비() {
        return Stream.of(
                new RefundShippingFee(3_000L, 3_000L, 0L),
                new RefundShippingFee(0L, 0L, 3_000L));
    }
}
