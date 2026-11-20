package com.ok.order;

/**
 * order 모듈이 소유한 MySQL 스키마(데이터베이스) 이름. 모듈 간 조인과 외래키는 두지 않는다.
 */
public final class OrderSchema {

    public static final String NAME = "paldogotgan_order";

    private OrderSchema() {
    }
}
