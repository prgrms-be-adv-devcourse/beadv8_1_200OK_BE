package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.testsupport.AbstractIntegrationTest;

// 시간 제한은 module-test.yml의 1초다. 다른 커넥션이 상품 테이블에 쓰기 잠금을 잡아 목록 SQL이 1초 넘게 기다리게 한다.
// 일반 SELECT는 MVCC로 읽어 행 잠금(FOR UPDATE)으로는 기다리지 않으므로 테이블 잠금을 쓴다
@AutoConfigureMockMvc
class BuyerProductQueryTimeoutIntegrationTest extends AbstractIntegrationTest {

	private static final String PRODUCT_TABLE = ProductSchema.NAME + ".product";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DataSource dataSource;

	@Autowired
	@Qualifier("productSearchJdbcClient")
	JdbcClient productSearchJdbcClient;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PlatformTransactionManager transactionManager;

	@ParameterizedTest
	@MethodSource("검색_방식별_검색어")
	void 목록_SQL이_시간_제한을_넘으면_503_PRODUCT_SEARCH_TIMEOUT이고_잠금이_풀리면_같은_조회가_정상이다(String keyword)
		throws Exception {
		// when & then
		whileProductTableLocked(() -> mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.type").value("https://api.example.com/problems/product/product-search-timeout"))
			.andExpect(jsonPath("$.status").value(503)));
		mockMvc.perform(get("/api/v1/products").param("keyword", keyword))
			.andExpect(status().isOk());
	}

	// 검색어 없음, 한 글자(품목), 두 글자 이상(FULLTEXT)이 서로 다른 SQL이라 모두 시간 제한이 걸리는지 본다
	static Stream<String> 검색_방식별_검색어() {
		return Stream.of("", "배", "감귤");
	}

	@Test
	void MySQL_드라이버의_시간_초과는_Spring_QueryTimeoutException으로_바뀐다() throws Exception {
		// when & then
		whileProductTableLocked(() -> assertThatThrownBy(() -> productSearchJdbcClient
			.sql("SELECT COUNT(*) FROM " + PRODUCT_TABLE).query(Long.class).single())
			.isInstanceOf(QueryTimeoutException.class)
			.hasRootCauseInstanceOf(SQLTimeoutException.class));
	}

	@Test
	void 전용_JdbcClient는_서비스의_읽기_트랜잭션과_같은_커넥션을_쓴다() {
		// given
		TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
		readOnly.setReadOnly(true);

		// when & then
		readOnly.executeWithoutResult(status -> {
			Long searchConnection = productSearchJdbcClient.sql("SELECT CONNECTION_ID()").query(Long.class).single();
			Long defaultConnection = jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class);
			Long readOnlyFlag = productSearchJdbcClient.sql("SELECT @@transaction_read_only").query(Long.class).single();
			assertThat(searchConnection).isEqualTo(defaultConnection);
			assertThat(readOnlyFlag).isEqualTo(1L);
		});
	}

	// 테이블 잠금은 잡은 세션에서만 풀 수 있고, 풀지 않고 풀에 돌려주면 공유 컨테이너의 다른 테스트가 멈춘다.
	// 그래서 커넥션을 직접 빌려 잡고 finally에서 같은 커넥션으로 푼 뒤 돌려준다
	private void whileProductTableLocked(ThrowingRunnable action) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("LOCK TABLES " + PRODUCT_TABLE + " WRITE");
			try {
				action.run();
			} finally {
				statement.execute("UNLOCK TABLES");
			}
		}
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
