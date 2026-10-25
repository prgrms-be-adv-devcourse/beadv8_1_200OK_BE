package com.ok.delivery.service.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionResultTest {

    @Test
    void 일반_지역은_도서산간이_아니다() {
        // given
        RegionResult sut = RegionResult.general();

        // when & then
        assertThat(sut.isRemoteArea()).isFalse();
    }

    @Test
    void 추가_운임이_0원이어도_일반_지역이_아니면_도서산간이다() {
        // given
        RegionResult sut = new RegionResult("MOUNTAIN", 0, 1);

        // when & then
        assertThat(sut.isRemoteArea()).isTrue();
    }
}
