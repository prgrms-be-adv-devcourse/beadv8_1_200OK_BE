package com.ok.member.member.application.port.in;

import com.ok.member.member.application.dto.AdminAccountCommand;
import com.ok.member.member.application.dto.AdminAccountCreation;

/**
 * 관리자 계정 생성 유스케이스. 앱이 시작될 때 설정값으로 최초 관리자를 만드는 데 쓴다.
 */
public interface AdminAccountUseCase {

    /**
     * 같은 아이디의 회원이 없을 때만 관리자 계정을 만든다.
     * 이미 있으면 만들지 않고 비밀번호도 바꾸지 않는다. 재시작할 때마다 덮어쓰면, 바꾼 비밀번호가 설정값으로 조용히 되돌아간다.
     *
     * @return 만들었는지, 이미 관리자가 있는지, 일반 회원이 아이디를 쓰고 있는지
     * @throws IllegalStateException 아이디는 비어 있지만 이메일을 다른 회원이 쓰고 있으면. 설정값을 고쳐야 하므로 앱 시작을 막는다
     */
    AdminAccountCreation createAdminIfAbsent(AdminAccountCommand command);

}
