package com.ok.refund.controller.dto;

import com.ok.refund.domain.RefundStatus;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 컨트롤러는 도메인 enum을 쓰지 않아 상태 허용 값을 문자열 패턴으로 따로 적었다.
// 상태가 늘었는데 패턴을 안 고치면 새 상태로 거를 수 없고, 패턴에만 있는 값은 서비스의 valueOf에서 500이 된다.
// 그래서 두 목록이 같은지 여기서 확인한다
class SellerRefundPageRequestTest {

    @Test
    void 상태_패턴의_허용_값이_환불_상태와_같다() throws NoSuchFieldException {
        // given
        Pattern pattern = SellerRefundPageRequest.class.getDeclaredField("status").getAnnotation(Pattern.class);
        List<String> statusNames = new ArrayList<>();
        for (RefundStatus status : RefundStatus.values()) {
            statusNames.add(status.name());
        }

        // when
        List<String> allowed = List.of(pattern.regexp().split("\\|"));

        // then
        assertThat(allowed).containsExactlyInAnyOrderElementsOf(statusNames);
    }
}
