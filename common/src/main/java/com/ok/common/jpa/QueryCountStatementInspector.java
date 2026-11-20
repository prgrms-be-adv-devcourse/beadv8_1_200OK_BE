package com.ok.common.jpa;

import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hibernate가 SQL 문을 준비할 때마다 {@link QueryCountContext}의 카운트를 올린다. SQL은 바꾸지 않는다.
 *
 * <p>JDBC 배치로 묶여 나가는 INSERT·UPDATE는 문이 한 번만 준비되므로 한 번으로 센다.
 * 서버와 DB 사이를 오가는 횟수에 가까운 값이고, N+1은 같은 모양의 SELECT가 요청 안에서 반복되어 이 값이 커지는 것으로 드러난다.
 * Hibernate를 거치지 않는 JDBC 호출(JdbcTemplate 등)은 세지 않는다.
 */
public class QueryCountStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        QueryCountContext.increment();
        return sql;
    }
}
