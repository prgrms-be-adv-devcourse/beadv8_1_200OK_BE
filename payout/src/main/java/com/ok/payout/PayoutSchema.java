package com.ok.payout;

/**
 * payout 모듈이 소유한 MySQL 스키마(데이터베이스) 이름.
 *
 * <p>엔티티의 {@code @Table(catalog = ...)}와 모듈의 Flyway 인스턴스가 이 값을 쓴다. 어노테이션 값은 상수여야 해서
 * 환경마다 바꾸지 않고 고정한다. 한 MySQL 서버 안에서 모듈이 스키마를 나눠 쓰고, 모듈 간 조인·외래키는 두지 않는다.
 */
public final class PayoutSchema {

    public static final String NAME = "paldogotgan_payout";

    private PayoutSchema() {
    }
}
