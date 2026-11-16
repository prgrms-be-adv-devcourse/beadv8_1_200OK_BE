package com.ok.delivery.adapter.in.event;

import com.ok.delivery.adapter.in.event.dto.SellerOriginPostalCodeMessage;
import com.ok.delivery.application.dto.SellerOriginPostalCodeCommand;
import com.ok.delivery.application.port.in.SellerOriginPostalCodeUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

// 단위 테스트: Spring·Kafka 없이, 입력 포트는 Mockito 가짜 객체로 대체
@ExtendWith(MockitoExtension.class)
class SellerOriginPostalCodeKafkaListenerTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 13, 10, 0);

    @Mock
    private SellerOriginPostalCodeUseCase sellerOriginPostalCodeUseCase;

    private SellerOriginPostalCodeKafkaListener sut;

    @BeforeEach
    void setUp() {
        sut = new SellerOriginPostalCodeKafkaListener(sellerOriginPostalCodeUseCase);
    }

    @Test
    void 메시지를_입력으로_바꿔_입력_포트를_부른다() {
        // given
        SellerOriginPostalCodeMessage message = new SellerOriginPostalCodeMessage(10L, "63100", OCCURRED_AT);

        // when
        sut.onKafka(message);

        // then
        then(sellerOriginPostalCodeUseCase).should()
                .apply(new SellerOriginPostalCodeCommand(10L, "63100", OCCURRED_AT));
    }

    @ParameterizedTest
    @MethodSource("값이_틀린_메시지")
    void 값이_틀린_메시지는_예외를_던지고_입력_포트를_부르지_않는다(SellerOriginPostalCodeMessage message) {
        // when & then
        assertThatThrownBy(() -> sut.onKafka(message)).isInstanceOf(IllegalArgumentException.class);
        then(sellerOriginPostalCodeUseCase).shouldHaveNoInteractions();
    }

    static Stream<SellerOriginPostalCodeMessage> 값이_틀린_메시지() {
        return Stream.of(
                new SellerOriginPostalCodeMessage(null, "63100", OCCURRED_AT),
                new SellerOriginPostalCodeMessage(10L, "63100", null),
                new SellerOriginPostalCodeMessage(10L, "abc", OCCURRED_AT)
        );
    }

    @Test
    void 입력_포트에서_예외가_나면_그대로_던진다() {
        // given: 값은 맞지만 입력 포트가 DB 오류로 실패
        RuntimeException failure = new RuntimeException("DB 오류");
        willThrow(failure).given(sellerOriginPostalCodeUseCase).apply(any());

        // when & then
        assertThatThrownBy(() -> sut.onKafka(new SellerOriginPostalCodeMessage(10L, "63100", OCCURRED_AT)))
                .isSameAs(failure);
    }
}
