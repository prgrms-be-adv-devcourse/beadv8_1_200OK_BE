package com.ok.member.member.application.dto;

/**
 * 비밀번호 변경 입력. 값은 요청 DTO에서 형식 검증을 마친 상태다.
 *
 * @param currentPassword 본인 확인에 쓰는 지금 비밀번호 (평문)
 * @param newPassword     바꿀 비밀번호 (평문). 인코딩은 서비스가 한다
 */
public record PasswordChangeCommand(
        String currentPassword,
        String newPassword
) {

    /**
     * 비밀번호가 로그에 남지 않도록 마스킹한다.
     */
    @Override
    public String toString() {
        return "PasswordChangeCommand[currentPassword=****, newPassword=****]";
    }

}
