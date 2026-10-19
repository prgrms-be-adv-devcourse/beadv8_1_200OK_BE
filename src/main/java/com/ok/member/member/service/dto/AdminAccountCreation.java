package com.ok.member.member.service.dto;

/**
 * 최초 관리자 계정을 만든 결과. 실행한 쪽이 결과에 맞는 로그를 남기는 데 쓴다.
 */
public enum AdminAccountCreation {

    /** 관리자 계정을 새로 만들었다. */
    CREATED,

    /** 같은 아이디의 관리자가 이미 있어 아무것도 하지 않았다. 비밀번호도 바꾸지 않는다. */
    ALREADY_EXISTS,

    /** 같은 아이디를 일반 회원이 쓰고 있어 관리자를 만들지 못했다. 설정한 아이디를 바꿔야 한다. */
    LOGIN_ID_TAKEN

}
