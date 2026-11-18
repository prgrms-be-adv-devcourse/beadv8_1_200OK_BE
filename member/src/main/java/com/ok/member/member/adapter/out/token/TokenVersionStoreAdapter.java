package com.ok.member.member.adapter.out.token;

import com.ok.common.security.TokenVersionStore;
import com.ok.member.member.application.port.out.TokenVersionRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link TokenVersionRecorder}를 common의 {@link TokenVersionStore}로 구현한다.
 * 토큰 검증기와 같은 저장소·키 형식을 써야 하므로 저장 방식은 common에 맡기고, 여기서는 옮기기만 한다.
 */
@Component
@RequiredArgsConstructor
public class TokenVersionStoreAdapter implements TokenVersionRecorder {

    private final TokenVersionStore tokenVersionStore;

    @Override
    public void record(Long memberId, long tokenVersion, Duration ttl) {
        tokenVersionStore.saveMinVersion(memberId, tokenVersion, ttl);
    }

}
