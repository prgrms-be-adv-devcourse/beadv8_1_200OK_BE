package com.ok.member.member.service;

import com.ok.member.member.domain.Member;
import com.ok.member.member.domain.MemberType;
import com.ok.member.member.repository.MemberRepository;
import com.ok.member.member.service.dto.AdminAccountCommand;
import com.ok.member.member.service.dto.AdminAccountCreation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 관리자 계정을 만든다. 지금은 앱 시작 시 최초 관리자를 만드는 데 쓴다.
 *
 * <p>회원가입({@link MemberSignupService})과 달리 회원 가입 이벤트를 발행하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAccountService {

    // 최초 관리자는 사람이 아니라 운영 계정이라 이름과 닉네임을 정해 둔다. 사람별 관리자는 관리자 생성 API에서 받는다
    static final String ADMIN_NAME = "관리자";
    static final String ADMIN_NICKNAME = "관리자00";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * 같은 아이디의 회원이 없을 때만 관리자 계정을 만든다.
     * 이미 있으면 만들지 않고 비밀번호도 바꾸지 않는다. 재시작할 때마다 덮어쓰면, 바꾼 비밀번호가 설정값으로 조용히 되돌아간다.
     *
     * @return 만들었는지, 이미 관리자가 있는지, 일반 회원이 아이디를 쓰고 있는지
     * @throws IllegalStateException 아이디는 비어 있지만 이메일을 다른 회원이 쓰고 있으면. 설정값을 고쳐야 하므로 앱 시작을 막는다
     */
    @Transactional
    public AdminAccountCreation createAdminIfAbsent(AdminAccountCommand command) {
        Optional<Member> existing = memberRepository.findByLoginId(command.loginId());
        if (existing.isPresent()) {
            return existing.get().getType() == MemberType.ADMIN
                    ? AdminAccountCreation.ALREADY_EXISTS
                    : AdminAccountCreation.LOGIN_ID_TAKEN;
        }
        // 설정값 오류라 사용자 입력 오류(4xx)가 아니다. 앱 시작을 막아 운영자가 바로 알게 한다
        if (memberRepository.existsByEmail(command.email())) {
            throw new IllegalStateException(
                    "관리자 이메일(%s)을 이미 다른 회원이 쓰고 있습니다. ADMIN_BOOTSTRAP_EMAIL을 바꿔 주세요."
                            .formatted(command.email()));
        }

        Member admin = Member.createAdmin(
                command.loginId(),
                passwordEncoder.encode(command.password()),
                command.email(),
                ADMIN_NAME,
                ADMIN_NICKNAME,
                LocalDateTime.now(clock)
        );
        memberRepository.save(admin);
        return AdminAccountCreation.CREATED;
    }

}
