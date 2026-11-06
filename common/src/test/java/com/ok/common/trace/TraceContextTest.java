package com.ok.common.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TraceContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 범위를_열면_traceId가_MDC에_들어가고_닫으면_진입_전_MDC_전체로_돌아간다() {
        // given
        MDC.put("traceId", "old");
        MDC.put("other", "keep");
        Map<String, String> before = MDC.getCopyOfContextMap();

        // when
        String inside;
        try (TraceContext.Scope scope = TraceContext.open("111")) {
            inside = TraceContext.currentTraceId();
            TraceContext.bindOrderId(5L);
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(inside).isEqualTo("111");
            softly.assertThat(MDC.getCopyOfContextMap()).isEqualTo(before);
        });
    }

    @Test
    void 진입_전_MDC가_비어_있었으면_닫은_뒤에도_비어_있다() {
        // when
        try (TraceContext.Scope scope = TraceContext.open("111")) {
            TraceContext.bindShipmentId(9L);
        }

        // then
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void 새_범위는_이전_흐름의_주문_번호를_이어받지_않는다() {
        // given
        MDC.put(TraceContext.ORDER_ID, "7");

        // when
        try (TraceContext.Scope scope = TraceContext.open("111")) {
            // then
            assertThat(MDC.get(TraceContext.ORDER_ID)).isNull();
        }
    }

    @Test
    void 주문_번호와_배송_번호는_문자열로_MDC에_붙는다() {
        // when
        try (TraceContext.Scope scope = TraceContext.open("111")) {
            TraceContext.bindOrderId(1024L);
            TraceContext.bindShipmentId(77L);

            // then
            assertSoftly(softly -> {
                softly.assertThat(MDC.get("orderId")).isEqualTo("1024");
                softly.assertThat(MDC.get("shipmentId")).isEqualTo("77");
            });
        }
    }

    @Test
    void null을_붙이면_해당_키를_지운다() {
        // given
        try (TraceContext.Scope scope = TraceContext.open("111")) {
            TraceContext.bindOrderId(1L);

            // when
            TraceContext.bindOrderId(null);

            // then
            assertThat(MDC.get("orderId")).isNull();
        }
    }

    @Test
    void 범위_밖에서는_traceId가_없다() {
        // when & then
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void 범위가_중첩되면_안쪽을_닫을_때_바깥_범위로_돌아간다() {
        // when
        try (TraceContext.Scope outer = TraceContext.open("1")) {
            TraceContext.bindOrderId(3L);
            try (TraceContext.Scope inner = TraceContext.open("2")) {
                assertThat(TraceContext.currentTraceId()).isEqualTo("2");
            }

            // then
            assertSoftly(softly -> {
                softly.assertThat(TraceContext.currentTraceId()).isEqualTo("1");
                softly.assertThat(MDC.get("orderId")).isEqualTo("3");
            });
        }
    }

    @Test
    void 키_상수는_로그_스키마의_필드_이름과_같다() {
        // when & then
        assertThat(new String[] {TraceContext.TRACE_ID, TraceContext.ORDER_ID, TraceContext.SHIPMENT_ID,
                TraceContext.MODULE, TraceContext.STEP, TraceContext.EVENT_TYPE})
                .containsExactly("traceId", "orderId", "shipmentId", "module", "step", "eventType");
    }

    @Test
    void traceId가_비어_있으면_거부한다() {
        // when & then
        assertThatThrownBy(() -> TraceContext.open(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceId는 필수입니다.");
    }
}
