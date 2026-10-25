package com.ok.member.member.controller.dto;

import com.ok.member.member.service.dto.MemberInfoUpdateCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class MemberInfoUpdateRequestTest {

    @Test
    void 이름과_닉네임은_앞뒤_공백만_제거하고_대소문자는_유지한다() {
        // when
        MemberInfoUpdateRequest sut = new MemberInfoUpdateRequest("  Lee Seoyeon ", " WeekendMarket ");

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.name()).isEqualTo("Lee Seoyeon");
            softly.assertThat(sut.nickname()).isEqualTo("WeekendMarket");
        });
    }

    @Test
    void 커맨드로_바꿀_때_이름과_닉네임이_뒤바뀌지_않는다() {
        // given
        MemberInfoUpdateRequest request = new MemberInfoUpdateRequest("이서연", "주말장보기");

        // when
        MemberInfoUpdateCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.name()).isEqualTo("이서연");
            softly.assertThat(sut.nickname()).isEqualTo("주말장보기");
        });
    }

}
