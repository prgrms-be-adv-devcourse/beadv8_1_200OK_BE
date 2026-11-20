package com.ok.product.product.adapter.out.persistence;

import java.time.Duration;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 구매자 상품 목록 SQL에만 시간 제한({@code product.search.query-timeout})을 거는 JdbcClient를 만든다.
 * 검색 SQL은 상품 전체에 걸쳐 점수와 품절을 계산하므로 느려질 수 있다. 제한이 없으면 느린 검색이 커넥션을 오래 잡아
 * 같은 풀을 쓰는 주문·결제 같은 다른 기능까지 커넥션을 기다리게 된다.
 * 기본 JdbcTemplate·JdbcClient는 다른 모듈도 쓰므로 바꾸지 않고, 이 빈은 기본 후보에서 빼서 이름으로 고를 때만 주입된다.
 * 같은 DataSource를 쓰므로 서비스의 읽기 트랜잭션에 그대로 참여한다.
 */
@Configuration(proxyBeanMethods = false)
class BuyerProductQueryJdbcConfig {

	static final String JDBC_CLIENT = "productSearchJdbcClient";

	// defaultCandidate = false라 자동 설정의 기본 JdbcClient가 물러나지 않는다(@ConditionalOnMissingBean이 기본 후보만 센다)
	@Bean(name = JDBC_CLIENT, defaultCandidate = false)
	JdbcClient productSearchJdbcClient(DataSource dataSource,
		@Value("${product.search.query-timeout}") Duration queryTimeout) {
		// JDBC queryTimeout은 초 단위라 1초 미만은 0초, 곧 제한 없음이 된다
		if (queryTimeout.toSeconds() < 1) {
			throw new IllegalArgumentException("product.search.query-timeout은 1초 이상이어야 합니다.");
		}
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.setQueryTimeout(Math.toIntExact(queryTimeout.toSeconds()));
		return JdbcClient.create(jdbcTemplate);
	}
}
