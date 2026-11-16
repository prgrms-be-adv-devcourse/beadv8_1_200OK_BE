package com.ok.carriersimulator.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ExposureWarningTest {

    @ParameterizedTest
    @MethodSource("주소와_노출_여부")
    void 이_컴퓨터_밖에서_접속되는_주소인지_가린다(String host, boolean expected) throws UnknownHostException {
        // given
        InetAddress address = InetAddress.getByName(host);

        // when
        boolean sut = ExposureWarning.isExposed(address);

        // then
        assertThat(sut).isEqualTo(expected);
    }

    static Stream<Arguments> 주소와_노출_여부() {
        return Stream.of(
                Arguments.of("127.0.0.1", false),
                Arguments.of("::1", false),
                Arguments.of("0.0.0.0", true),
                Arguments.of("192.168.0.10", true));
    }

    @Test
    void 주소를_정하지_않으면_모든_주소에서_받으므로_노출이다() {
        // when
        boolean sut = ExposureWarning.isExposed(null);

        // then
        assertThat(sut).isTrue();
    }
}
