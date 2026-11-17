package com.ok.member.member.adapter.in.web.dto;

import com.ok.member.member.application.dto.PasswordChangeCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class PasswordChangeRequestTest {

    @Test
    void toCommand는_현재_비밀번호와_새_비밀번호를_바꾸지_않고_제자리에_옮긴다() {
        // given: 공백도 비밀번호의 일부라 정규화하지 않는다
        PasswordChangeRequest request = new PasswordChangeRequest(" current1! ", "newPassword1!");

        // when
        PasswordChangeCommand sut = request.toCommand();

        // then
        assertThat(sut).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(sut.currentPassword()).isEqualTo(" current1! ");
            softly.assertThat(sut.newPassword()).isEqualTo("newPassword1!");
        });
    }

    @Test
    void toString에_비밀번호가_노출되지_않는다() {
        // given
        PasswordChangeRequest request = new PasswordChangeRequest("current1!", "newPassword1!");

        // when
        String sut = request.toString() + request.toCommand().toString();

        // then
        assertThat(sut).doesNotContain("current1!", "newPassword1!");
    }

}
