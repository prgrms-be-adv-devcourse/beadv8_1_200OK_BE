package com.ok.member.member.service;

import com.ok.common.event.MemberRegisteredEvent;
import com.ok.member.member.domain.Member;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.SignupCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class MemberSignupService {

    private final MemberRepository memberRepository;
    private final MemberDuplicateValidator memberDuplicateValidator;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    // 현재 시각 기준 (전역 ClockConfig, JVM 시간대 = Asia/Seoul)
    // 약관 동의 일시를 LocalDateTime.now()로 직접 만들면 실행 환경의 시간대를 따라가고, 테스트에서 값을 고정할 수 없다 (#93)
    private final Clock clock;

    @Transactional
    public Long signup(SignupCommand command) {
        // 중복 확인
        memberDuplicateValidator.validate(command.loginId(), command.email());
        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(command.password());

        // 회원 엔티티 생성
        Member member = Member.createBuyer(
                command.loginId(),
                encodedPassword,
                command.email(),
                command.name(),
                command.nickname(),
                LocalDateTime.now(clock)
        );

        // 회원 저장
        Member saved = save(member);

        // 회원 가입 이벤트 발행
        eventPublisher.publishEvent(new MemberRegisteredEvent(saved.getId()));

        return saved.getId();
    }

    /**
     * 동시 가입 대비 (unique 제약 위반을 잡아 중복 예외로 변환)
     */
    private Member save(Member member) {
        try {
            return memberRepository.saveAndFlush(member);
        } catch (DataIntegrityViolationException e) {
            throw memberDuplicateValidator.toDuplicateException(e);
        }
    }

}
