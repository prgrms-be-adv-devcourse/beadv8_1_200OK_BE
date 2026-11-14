package com.ok.carriersimulator.web;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.ok.carriersimulator.core.CarrierSimulator;
import com.ok.carriersimulator.testsupport.SimulatorClient;
import com.ok.carriersimulator.testsupport.SimulatorClient.HttpResult;
import com.ok.carriersimulator.testsupport.SimulatorWebTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SimulatorWebTest
class TrackGraphQlUnexpectedErrorTest {

    private static final String TRACK_QUERY = """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                lastEvent { status { code } }
              }
            }
            """;

    @Autowired
    private SimulatorClient client;
    @MockitoSpyBean
    private CarrierSimulator simulator;

    @Test
    void 예상_못_한_예외는_INTERNAL로_오고_예외_내용은_밖으로_나가지_않는다() {
        // given
        String token = client.token();
        doThrow(new IllegalStateException("내부 사정")).when(simulator).track(any(), any());

        // when
        HttpResult sut = client.graphql(TRACK_QUERY, Map.of("carrierId", "kr.cjlogistics", "trackingNumber", "901000000001"), token);

        // then
        assertSoftly(softly -> {
            softly.assertThat(sut.status()).isEqualTo(200);
            softly.assertThat(sut.<String>read("$.errors[0].extensions.code")).isEqualTo("INTERNAL");
            softly.assertThat(sut.<String>read("$.errors[0].extensions.classification")).isEqualTo("INTERNAL_ERROR");
            softly.assertThat(sut.body()).doesNotContain("내부 사정");
        });
    }
}
