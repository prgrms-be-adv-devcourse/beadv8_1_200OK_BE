package com.ok.payout.application.service;

import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

// DB 무결성 오류가 중복 키(유니크 위반)인지 보는 도우미
// 해결 기록(#568)과 조정 생성(#291)이 함께 씀: 둘 다 "동시에 들어온 두 번째 요청"을 유니크 위반으로 막고 409로 바꾸는데,
//   FK(1452)·NOT NULL·길이 위반 같은 코드 버그까지 409로 가리지 않으려고 중복 키만 골라야 함
final class DuplicateKeyErrors {

    // MySQL 오류 번호 1062(ER_DUP_ENTRY, 중복 키). 오류 메시지 글자는 DB 버전·언어 설정에 따라 바뀔 수 있어 번호로 봄
    private static final int MYSQL_DUPLICATE_KEY_ERROR = 1062;

    private DuplicateKeyErrors() {
    }

    // 원인 사슬에서 DB 드라이버 예외(SQLException)를 찾아 오류 번호가 중복 키(1062)인지 봄 (통합 테스트에서 실제 MySQL로 확인)
    static boolean isDuplicateKey(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
