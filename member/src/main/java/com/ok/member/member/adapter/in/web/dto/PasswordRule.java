package com.ok.member.member.adapter.in.web.dto;

/**
 * 회원이 정하는 비밀번호의 형식 규칙. 회원가입과 비밀번호 변경이 함께 쓴다.
 * 두 곳의 규칙이 어긋나면 가입할 때는 막히는 비밀번호로 바꿀 수 있게 되므로 한곳에 둔다.
 */
final class PasswordRule {

    /** 영문, 숫자, 특수문자를 모두 포함한 8~20자 */
    static final String REGEX = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z\\d]).{8,20}$";

    static final String MESSAGE = "비밀번호는 영문, 숫자, 특수문자를 모두 포함해 8~20자여야 합니다.";

    private PasswordRule() {
    }

}
