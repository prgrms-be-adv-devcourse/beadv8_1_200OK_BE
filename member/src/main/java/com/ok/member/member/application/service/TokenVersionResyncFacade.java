package com.ok.member.member.application.service;

import com.ok.member.member.application.dto.TokenVersionChange;
import com.ok.member.member.application.port.in.TokenVersionResyncUseCase;
import com.ok.member.member.application.port.out.TokenVersionRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 토큰 검증기가 읽는 저장소의 토큰 최소 버전을 회원 DB 값으로 다시 채운다.
 * DB가 원본이고 저장소는 사본이라, 저장소가 비거나 쓰기에 실패해도 DB에서 다시 만들면 된다. 장애 중 비밀번호 변경, AOF 유실,
 * 클러스터 장애 조치, 볼륨 손실을 모두 같은 방법으로 메운다.
 * 조회와 저장 사이에 비밀번호가 다시 바뀌면 이 실행이 옛 버전을 쓰게 되지만, 저장소가 더 높은 값만 받으므로({@code TokenVersionStore#saveMinVersion})
 * 새 버전을 낮추지 않는다. 대부분의 회원은 이미 같은 값이 있어 실제로 쓰이지 않는다.
 * 저장소 호출(장애 때 건마다 최대 1초) 동안 DB 커넥션을 잡지 않도록 조회만 {@link MemberService}의 트랜잭션에서 하고 저장은 밖에서 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenVersionResyncFacade implements TokenVersionResyncUseCase {

    private final MemberService memberService;
    private final TokenVersionRecorder tokenVersionRecorder;
    private final TokenVersionRecordPolicy tokenVersionRecordPolicy;

    @Override
    public int resync() {
        List<TokenVersionChange> changes = memberService.findTokenVersionChangesSince(tokenVersionRecordPolicy.resyncFrom());
        int recorded = 0;
        for (int i = 0; i < changes.size(); i++) {
            TokenVersionChange change = changes.get(i);
            Optional<Duration> ttl = tokenVersionRecordPolicy.remainingTtl(change.changedAt());
            if (ttl.isEmpty()) {
                continue;
            }
            try {
                tokenVersionRecorder.record(change.memberId(), change.tokenVersion(), ttl.get());
                recorded++;
            } catch (DataAccessException e) {
                // 저장소 장애만 잡는다. 그 밖의 예외는 버그라 스케줄러까지 올려 오류 로그로 드러낸다. 비밀번호 변경(MemberPasswordFacade)이
                // 모든 예외를 잡는 것과 다른 이유는, 그쪽은 이미 커밋된 요청이 500이 되면 안 되지만 여기는 다음 실행이 다시 시도하기 때문이다.
                // 실패는 거의 저장소 장애라 남은 건도 건마다 타임아웃만큼 기다리다 실패할 뿐이다. 멈추고 다음 실행에 맡긴다.
                // 남은 건수는 실패한 건부터 센다. 앞에서 남길 시간이 지나 건너뛴 건은 남은 건이 아니다
                log.warn("토큰 버전 저장소에 접근하지 못해 다시 채우기를 멈춘다. 다음 실행에서 다시 채운다. 남긴 건수={}, 남은 건수={}, cause={}",
                        recorded, changes.size() - i, e.getMessage());
                break;
            }
        }
        return recorded;
    }

}
