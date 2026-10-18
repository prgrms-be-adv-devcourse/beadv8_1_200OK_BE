package com.ok.member.seller.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 판매자 신청의 심사 상태를 나타낸다.
 * 신청하면 {@code PENDING}, 관리자가 심사를 시작하면 {@code IN_REVIEW},
 * 결과에 따라 {@code APPROVED} 또는 {@code REJECTED}가 된다.
 */
public enum SellerApplicationStatus {

    /** 신청 완료, 심사 대기 */
    PENDING,

    /** 관리자가 심사 중 */
    IN_REVIEW,

    /** 승인. 이 회원은 판매자다. */
    APPROVED,

    /** 거절. 재신청 대기 기간 없이 바로 다시 신청할 수 있다. */
    REJECTED;

    /**
     * 새 신청을 막는 상태(거절을 뺀 상태) 목록을 반환한다.
     * DB의 {@code active_*} 생성 컬럼 조건(거절이 아님)과 같아야 한다. 한쪽을 바꾸면 다른 쪽도 함께 바꾼다.
     */
    public static Set<SellerApplicationStatus> activeStatuses() {
        return EnumSet.of(PENDING, IN_REVIEW, APPROVED);
    }

}
