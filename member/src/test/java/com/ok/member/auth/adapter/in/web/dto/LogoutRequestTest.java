package com.ok.member.auth.adapter.in.web.dto;

import com.ok.member.auth.application.dto.LogoutCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogoutRequestTest {

    private static final String REFRESH_TOKEN = "refresh-token-value";

    @Test
    void toCommand는_Refresh_Token을_그대로_옮긴다() {
        // given
        LogoutRequest request = new LogoutRequest(REFRESH_TOKEN);

        // when
        LogoutCommand sut = request.toCommand();

        // then
        assertThat(sut.refreshToken()).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    void toString에_Refresh_Token이_노출되지_않는다() {
        // given
        LogoutRequest request = new LogoutRequest(REFRESH_TOKEN);

        // when
        String sut = request.toString() + request.toCommand().toString();

        // then
        assertThat(sut).doesNotContain(REFRESH_TOKEN);
    }

}
