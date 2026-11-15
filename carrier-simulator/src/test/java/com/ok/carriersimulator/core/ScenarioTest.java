package com.ok.carriersimulator.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScenarioTest {

    @ParameterizedTest
    @MethodSource("송장_번호와_시나리오")
    void 송장_번호_앞자리로_시나리오를_고른다(String trackingNumber, Scenario expected) {
        // when
        Scenario sut = Scenario.of(trackingNumber);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    static Stream<Arguments> 송장_번호와_시나리오() {
        return Stream.of(
                Arguments.of("901000000001", Scenario.NORMAL),
                Arguments.of("902000000001", Scenario.SLOW),
                Arguments.of("903000000001", Scenario.STUCK),
                Arguments.of("904000000001", Scenario.NOT_FOUND),
                Arguments.of("905000000001", Scenario.LATE_REGISTER),
                Arguments.of("906000000001", Scenario.REDELIVERY),
                Arguments.of("907000000001", Scenario.LATE_INSERT),
                Arguments.of("908000000001", Scenario.EXCEPTION),
                // 규칙에 맞지 않으면 정상이다: 판매자가 넣은 번호, 없는 시나리오 자리, 자릿수가 다른 번호, 빈 값
                Arguments.of("1234567890", Scenario.NORMAL),
                Arguments.of("909900000001", Scenario.NORMAL),
                Arguments.of("90300000001", Scenario.NORMAL),
                Arguments.of("9030000000012", Scenario.NORMAL),
                Arguments.of("", Scenario.NORMAL),
                Arguments.of(null, Scenario.NORMAL));
    }

    @ParameterizedTest
    @MethodSource("모든_시나리오")
    void 만든_번호는_다시_같은_시나리오로_읽힌다(Scenario scenario) {
        // when
        String sut = scenario.trackingNumber(42);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).matches("\\d{12}");
            softly.assertThat(Scenario.of(sut)).isEqualTo(scenario);
        });
    }

    static Stream<Scenario> 모든_시나리오() {
        return Stream.of(Scenario.values());
    }

    @Test
    void 이력_끼어듦은_10시간_사건이_22시간에_보인다() {
        // when
        Scenario.Step sut = Scenario.LATE_INSERT.steps().get(2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.occursAt()).isEqualTo(Duration.ofHours(10));
            softly.assertThat(sut.visibleAt()).isEqualTo(Duration.ofHours(22));
            softly.assertThat(sut.status()).isEqualTo(StatusCode.IN_TRANSIT);
        });
    }

    @Test
    void 늦게_등록되는_송장은_6시간_동안_없다() {
        // when
        Scenario sut = Scenario.LATE_REGISTER;

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.steps().getFirst().occursAt()).isEqualTo(Duration.ofHours(6));
            softly.assertThat(sut.steps().getLast().occursAt()).isEqualTo(Duration.ofHours(36));
        });
    }

    @Test
    void 이력에_나타나는_순서의_단계_목록은_시나리오마다_하나를_함께_쓴다() {
        // when
        List<Scenario.Step> sut = Scenario.LATE_INSERT.stepsByVisibility();

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut).isSameAs(Scenario.LATE_INSERT.stepsByVisibility());
            softly.assertThat(sut).extracting(Scenario.Step::visibleAt).isSorted();
            // 10시간째 사건은 22시간째에 나타나므로 20시간째 사건 뒤에 온다
            softly.assertThat(sut).extracting(Scenario.Step::occursAt).containsExactly(
                    Duration.ofHours(0), Duration.ofHours(4), Duration.ofHours(20), Duration.ofHours(10),
                    Duration.ofHours(26), Duration.ofHours(30));
        });
    }
}
