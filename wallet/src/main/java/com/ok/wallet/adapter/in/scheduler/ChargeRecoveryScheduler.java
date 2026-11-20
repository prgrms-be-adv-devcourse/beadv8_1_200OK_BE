package com.ok.wallet.adapter.in.scheduler;

import com.ok.wallet.application.dto.ChargeRecoveryResult;
import com.ok.wallet.application.port.in.ChargeRecoveryUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 처리 중으로 멈춘 충전 건의 복구를 정해진 간격으로 부르고, 오래 남은 건을 알린다.
 * 서버가 여러 대면 함께 돈다. 따로 잠금(ShedLock 등)을 두지 않은 이유: 복구가 건마다 조건부 UPDATE로 맡아, 같은 건은 한 서버만
 * 토스에 묻는다. 서버마다 같은 목록을 훑더라도 맡지 못한 건은 바로 건너뛴다.
 * 테스트 프로필에서는 켜지 않는다. 통합 테스트가 만든 처리 중 건을 복구가 중간에 집으면 결과가 흔들리므로, 테스트는 유스케이스를 직접 부른다.
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ChargeRecoveryScheduler {
    private static final String LONG_IN_PROGRESS = "[CHARGE_LONG_IN_PROGRESS] 복구가 끝냈어야 할 만큼 오래 처리 중인 충전 건이 있습니다. 토스 결제를 확인해야 합니다.";

    private final ChargeRecoveryUseCase chargeRecovery;

    // 회차가 끝난 뒤 1분 쉬고 다시 돈다(fixedDelay). 앞 회차의 토스 조회가 느려도 회차가 겹치지 않는다.
    // 확인 시각은 건마다 따로 정해져 있어(처음 5분 뒤, 그 뒤 10분마다) 자주 깨어나도 토스에 더 자주 묻지 않고, 늦어도 1분 안에 집는다.
    // 앱이 뜬 직후 몰리지 않게 1분 뒤 시작한다
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void recover() {
        ChargeRecoveryResult result = chargeRecovery.recover();
        if (result.checked() > 0) {
            log.info("충전 복구 종료 확인={}, 완료={}, 실패={}, 멈춤={}, 다시 확인={}, 이미 끝남={}",
                    result.checked(), result.completed(), result.failed(), result.held(), result.retried(),
                    result.alreadyHandled());
        }
    }

    // 멈출 때 한 번 남기는 오류 로그를 놓쳐도 사람이 알 수 있게 한 시간마다 다시 알린다. 매 회차마다 남기면 같은 건이 너무 자주 찍힌다
    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public void warnLongInProgress() {
        List<String> chargeNos = chargeRecovery.findLongInProgressChargeNos();
        if (!chargeNos.isEmpty()) {
            log.warn("{} chargeNos={}", LONG_IN_PROGRESS, chargeNos);
        }
    }
}
