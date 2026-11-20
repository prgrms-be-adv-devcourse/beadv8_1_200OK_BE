package com.ok.member.member.adapter.in.scheduler;

import com.ok.member.member.application.port.in.TokenVersionResyncUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 토큰 검증기가 읽는 저장소의 토큰 최소 버전을 정해진 주기(기본 30초)로 회원 DB 값으로 다시 채운다(#561).
 *
 * <p>주기는 {@code member.token-version.resync.cron}으로 바꾼다. cron을 {@code -}로 두면 돌지 않는다(테스트는 끄고 직접 부른다).
 *
 * <p>서버가 여러 대면 서버마다 다른 시각에 DB를 읽어 서로 다른(더 낮은) 버전을 쓸 수 있다. 저장소가 지금보다 높은 값만 받으므로
 * 늦게 도착한 낮은 값은 버려지고, 그래서 잠금을 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TokenVersionResyncScheduler {

    private final TokenVersionResyncUseCase tokenVersionResync;

    // 30초마다. 빠진 기록이 메워지기까지 폐기된 Access Token이 통과하므로 Access Token 수명(30분)보다 훨씬 짧게 잡는다.
    // 대상은 최근 31분 안에 비밀번호를 바꾼 회원뿐이라, 대개 인덱스 조회 한 번으로 끝난다.
    // 특정 시각이 아니라 짧은 주기라 운영 서버가 떠 있는 동안(09~18시) 계속 돌고, 서버가 켜지면 첫 실행이 최근 31분치를 채운다
    @Scheduled(cron = "${member.token-version.resync.cron:*/30 * * * * *}", zone = "Asia/Seoul")
    public void run() {
        tokenVersionResync.resync();
    }

}
