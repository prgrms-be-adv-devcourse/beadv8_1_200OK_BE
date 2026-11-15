package com.ok.delivery.application.service;

import com.ok.delivery.application.dto.SellerOriginPostalCodeCommand;
import com.ok.delivery.application.port.out.SellerOriginPostalCodeRepository;
import com.ok.delivery.domain.SellerOriginPostalCode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerOriginPostalCodeServiceTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 11, 13, 10, 0);
    private static final LocalDateTime T2 = T1.plusMinutes(5);

    SellerOriginPostalCodeRepository repository = mock(SellerOriginPostalCodeRepository.class);

    SellerOriginPostalCodeService sut = new SellerOriginPostalCodeService(repository);

    @Test
    void 처음_받는_판매자는_새로_저장한다() {
        // given
        when(repository.findBySellerId(10L)).thenReturn(Optional.empty());

        // when
        sut.apply(new SellerOriginPostalCodeCommand(10L, "63100", T1));

        // then
        ArgumentCaptor<SellerOriginPostalCode> captor = ArgumentCaptor.forClass(SellerOriginPostalCode.class);
        verify(repository).save(captor.capture());
        SellerOriginPostalCode saved = captor.getValue();
        assertThat(saved).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(saved.getSellerId()).isEqualTo(10L);
            softly.assertThat(saved.getPostalCode()).isEqualTo("63100");
            softly.assertThat(saved.getSourceOccurredAt()).isEqualTo(T1);
        });
    }

    @Test
    void 이미_있는_판매자는_더_새로운_메시지로_덮어쓴다() {
        // given
        SellerOriginPostalCode stored = SellerOriginPostalCode.create(10L, "63100", T1);
        when(repository.findBySellerId(10L)).thenReturn(Optional.of(stored));

        // when
        sut.apply(new SellerOriginPostalCodeCommand(10L, "06236", T2));

        // then: 변경 감지로 저장하므로 save는 부르지 않는다
        assertSoftly(softly -> {
            softly.assertThat(stored.getPostalCode()).isEqualTo("06236");
            softly.assertThat(stored.getSourceOccurredAt()).isEqualTo(T2);
        });
        verify(repository, never()).save(any());
    }

    @Test
    void 오래된_메시지는_값을_바꾸지_않는다() {
        // given
        SellerOriginPostalCode stored = SellerOriginPostalCode.create(10L, "06236", T2);
        when(repository.findBySellerId(10L)).thenReturn(Optional.of(stored));

        // when
        sut.apply(new SellerOriginPostalCodeCommand(10L, "63100", T1));

        // then
        assertThat(stored.getPostalCode()).isEqualTo("06236");
        verify(repository, never()).save(any());
    }

    @Test
    void 같은_메시지가_다시_와도_결과가_같다() {
        // given
        SellerOriginPostalCode stored = SellerOriginPostalCode.create(10L, "63100", T1);
        when(repository.findBySellerId(10L)).thenReturn(Optional.of(stored));

        // when
        sut.apply(new SellerOriginPostalCodeCommand(10L, "63100", T1));

        // then
        assertSoftly(softly -> {
            softly.assertThat(stored.getPostalCode()).isEqualTo("63100");
            softly.assertThat(stored.getSourceOccurredAt()).isEqualTo(T1);
        });
    }

    @Test
    void 우편번호가_빈_메시지는_출고지를_비운다() {
        // given
        SellerOriginPostalCode stored = SellerOriginPostalCode.create(10L, "63100", T1);
        when(repository.findBySellerId(10L)).thenReturn(Optional.of(stored));

        // when
        sut.apply(new SellerOriginPostalCodeCommand(10L, null, T2));

        // then
        assertThat(stored.getPostalCode()).isNull();
    }
}
