package com.ok.common.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QueryCountContextTest {

    private final QueryCountStatementInspector inspector = new QueryCountStatementInspector();

    @Test
    void 열린_범위_안에서_준비된_SQL_수를_센다() {
        // given
        try (QueryCountContext.Scope sut = QueryCountContext.open()) {
            // when
            inspector.inspect("select 1");
            inspector.inspect("select 2");

            // then
            assertThat(sut.count()).isEqualTo(2);
        }
    }

    @Test
    void SQL_문자열은_바꾸지_않고_그대로_돌려준다() {
        // given
        String sql = "select * from product where id = ?";

        // when
        String sut = inspector.inspect(sql);

        // then
        assertThat(sut).isSameAs(sql);
    }

    @Test
    void 범위가_열리지_않은_스레드의_SQL은_세지_않고_예외도_없다() {
        // given & when
        inspector.inspect("select 1");

        // then: 이후에 연 범위는 0에서 시작한다
        try (QueryCountContext.Scope sut = QueryCountContext.open()) {
            assertThat(sut.count()).isZero();
        }
    }

    @Test
    void 범위를_닫으면_다음_범위는_0에서_다시_센다() {
        // given
        try (QueryCountContext.Scope first = QueryCountContext.open()) {
            inspector.inspect("select 1");
        }

        // when
        try (QueryCountContext.Scope sut = QueryCountContext.open()) {
            // then
            assertThat(sut.count()).isZero();
        }
    }

    @Test
    void 이미_열린_범위_안에서_다시_열면_같은_카운터를_쓰고_안쪽을_닫아도_바깥은_유지된다() {
        // given
        try (QueryCountContext.Scope outer = QueryCountContext.open()) {
            inspector.inspect("select 1");

            // when
            try (QueryCountContext.Scope inner = QueryCountContext.open()) {
                inspector.inspect("select 2");
            }
            inspector.inspect("select 3");

            // then
            assertThat(outer.count()).isEqualTo(3);
        }
    }
}
