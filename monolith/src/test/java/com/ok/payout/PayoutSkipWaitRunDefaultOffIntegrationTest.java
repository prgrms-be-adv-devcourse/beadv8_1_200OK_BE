package com.ok.payout;

import com.ok.PaldoGotganApplication;
import com.ok.common.exception.RestApiException;
import com.ok.payout.adapter.in.batch.PayoutJobRunner;
import com.ok.payout.exception.PayoutErrorCode;
import com.ok.testsupport.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// monolith는 정산 설정 파일(application-payout.yaml)과 module-test.yml을 읽지 않으므로
// payout.skip-wait-run.enabled 속성이 없다. 이때 대기 건너뛰기가 꺼져 있어야 한다는 기본값(false)을 확인한다 (#485)
// (payout 모듈 테스트는 module-test.yml로 켜 두기 때문에 이 기본값은 거기서 확인할 수 없다)
@SpringBootTest(classes = PaldoGotganApplication.class)
class PayoutSkipWaitRunDefaultOffIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    PayoutJobRunner payoutJobRunner;

    @Autowired
    Environment environment;

    @Test
    void 설정이_없으면_대기_건너뛰기_실행은_409로_거절된다() {
        // given: 이 환경에 속성이 없다는 전제가 깨지면(예: OS 환경 변수) 아래 단언이 기본값을 확인하지 못하므로 먼저 막는다
        assertThat(environment.containsProperty("payout.skip-wait-run.enabled")).isFalse();

        // when & then
        assertThatThrownBy(payoutJobRunner::runSkippingWait)
                .isInstanceOfSatisfying(RestApiException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(PayoutErrorCode.SKIP_WAIT_RUN_DISABLED));
    }
}
