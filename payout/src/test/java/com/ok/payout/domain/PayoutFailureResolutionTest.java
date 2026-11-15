package com.ok.payout.domain;

import com.ok.common.exception.RestApiException;
import com.ok.payout.exception.PayoutErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PayoutFailureResolutionTest {

    private static final String REQUEST_ID = "3f2b8c1e-7d4a-4b9e-9c21-5a6d8e0f1b23";

    @Test
    void 실패_기록의_주문과_주문상품을_복사해_해결_기록을_만든다() {
        // given
        PayoutFailure failure = savedFailure();

        // when
        PayoutFailureResolution sut = PayoutFailureResolution.of(failure, REQUEST_ID, PayoutResolutionMethod.REPROCESS, 1L, "메모");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.getResolutionRequestId()).isEqualTo(REQUEST_ID);
            softly.assertThat(sut.getFailureId()).isEqualTo(77L);
            softly.assertThat(sut.getOrderId()).isEqualTo(100L);
            softly.assertThat(sut.getOrderItemId()).isEqualTo(1001L);
            softly.assertThat(sut.getMethod()).isEqualTo(PayoutResolutionMethod.REPROCESS);
            softly.assertThat(sut.getAdminId()).isEqualTo(1L);
            softly.assertThat(sut.getMemo()).isEqualTo("메모");
        });
    }

    @ParameterizedTest
    @MethodSource("빈_메모")
    void 메모가_비었으면_422_에러다(String memo) {
        // when & then
        assertThatThrownBy(() -> PayoutFailureResolution.of(savedFailure(), REQUEST_ID, PayoutResolutionMethod.REPROCESS, 1L, memo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.MISSING_RESOLUTION_MEMO));
    }

    static Stream<String> 빈_메모() {
        return Stream.of(null, "", "   ");
    }

    @Test
    void 메모가_255자를_넘으면_422_에러다() {
        // given
        String memo = "가".repeat(PayoutFailureResolution.MAX_MEMO_LENGTH + 1);

        // when & then
        assertThatThrownBy(() -> PayoutFailureResolution.of(savedFailure(), REQUEST_ID, PayoutResolutionMethod.REPROCESS, 1L, memo))
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.RESOLUTION_MEMO_TOO_LONG));
    }

    @Test
    void 관리자_ID가_없으면_만들지_않는다() {
        // when & then
        assertThatThrownBy(() -> PayoutFailureResolution.of(savedFailure(), REQUEST_ID, PayoutResolutionMethod.REPROCESS, null, "메모"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해결 기록에는 실패 기록 ID, 해결 요청 번호, 해결 방법, 관리자 ID가 필요합니다.");
    }

    @Test
    void 해결_요청_번호가_없으면_만들지_않는다() {
        // when & then
        assertThatThrownBy(() -> PayoutFailureResolution.of(savedFailure(), null, PayoutResolutionMethod.REPROCESS, 1L, "메모"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("해결 기록에는 실패 기록 ID, 해결 요청 번호, 해결 방법, 관리자 ID가 필요합니다.");
    }

    // 저장된 실패 기록처럼 id를 채움 (엔티티에 id를 넣는 생성 경로가 없어 테스트에서만 넣음)
    private static PayoutFailure savedFailure() {
        PayoutCandidateItem candidate = PayoutCandidateItem.ofSaleAmount(100L, 1001L, 10L, 1_000L);
        ReflectionTestUtils.setField(candidate, "id", 5L);
        PayoutFailure failure = PayoutFailure.of(1L, candidate, PayoutFailureReason.VERIFY_MISMATCH);
        ReflectionTestUtils.setField(failure, "id", 77L);
        return failure;
    }
}
