package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.ok.product.product.adapter.out.persistence.BuyerProductQueryJdbcRepository;
import com.ok.product.product.adapter.out.persistence.ProductJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJpaRepository;
import com.ok.product.product.adapter.out.persistence.ProductTagJudgmentJpaRepository;
import com.ok.product.product.application.dto.ProductSearchBackfillResult;
import com.ok.product.product.application.service.ProductSearchBackfillService;
import com.ok.product.product.domain.ProductSearchKeyword;
import com.ok.product.product.domain.ProductSort;
import com.ok.testsupport.AbstractIntegrationTest;

// 검색 평가셋(search-eval/README.md)으로 구매자 검색의 유형별 recall@10과 MRR@10이 떨어지지 않았는지 본다.
// FULLTEXT 점수는 전체 문서 수(IDF)에 따라 달라지므로 다른 테스트가 남긴 상품을 비우고 시드 300개만으로 잰다
class ProductSearchEvalIntegrationTest extends AbstractIntegrationTest {

	private static final String SCHEMA = ProductSchema.NAME;
	private static final int TOP_K = 10;
	// 운영 하한(application-product.yaml)이다. 테스트 설정(module-test.yml)의 0.5는 상품 10개 안팎인 다른 테스트에 맞춘 값이라 쓰지 않는다
	private static final double PRODUCTION_MIN_SCORE = 1.0;
	// 시드와 평가셋이 고정이라 결과도 고정이다. 잰 값을 소수 둘째 자리에서 버린 값이라 0.01 이상 떨어지면 실패한다
	private static final Map<String, Double> RECALL_FLOORS = Map.of(
		"clean", 0.92,
		"spacing", 0.93,
		"typo", 0.80,
		"vague", 0.34);
	private static final Map<String, Double> MRR_FLOORS = Map.of(
		"clean", 0.87,
		"spacing", 0.89,
		"typo", 0.70,
		"vague", 0.46);

	@Autowired
	BuyerProductQueryJdbcRepository buyerProductQueryRepository;

	@Autowired
	ProductSearchBackfillService productSearchBackfillService;

	@Autowired
	ProductJpaRepository productRepository;

	@Autowired
	ProductTagJpaRepository productTagRepository;

	@Autowired
	ProductTagJudgmentJpaRepository productTagJudgmentRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeAll
	void loadSeed() throws IOException {
		deleteAll();
		String[] inserts = readLines("search-eval/seed.sql").stream()
			.filter(line -> line.startsWith("INSERT INTO "))
			// 시드는 스키마 없이 테이블 이름만 쓴다
			.map(line -> line.replaceFirst("^INSERT INTO (\\w+) ", "INSERT INTO " + SCHEMA + ".$1 "))
			.map(line -> line.substring(0, line.length() - 1))
			.toArray(String[]::new);
		jdbcTemplate.batchUpdate(inserts);
	}

	// 시드가 id를 직접 지정하므로 남겨 두면 다른 테스트의 상품 id와 겹친다
	@AfterAll
	void tearDown() {
		deleteAll();
	}

	@Test
	void 평가셋_검색어의_유형별_recall_10과_MRR_10이_하한_이상이다() throws IOException {
		// given
		ProductSearchBackfillResult backfill = productSearchBackfillService.backfill();
		int nonDeleted = count("product WHERE deleted_at IS NULL");
		assertThat(backfill).isEqualTo(new ProductSearchBackfillResult(nonDeleted, 0));
		assertThat(count("product_search")).isEqualTo(nonDeleted);
		List<EvalQuery> queries = readLines("search-eval/queries.csv").stream()
			.skip(1)
			.map(EvalQuery::parse)
			.toList();

		// when
		Map<String, List<QueryResult>> sut = queries.stream()
			.map(this::evaluate)
			.collect(Collectors.groupingBy(QueryResult::type, TreeMap::new, Collectors.toList()));

		// then
		String summary = sut.entrySet().stream()
			.map(entry -> ("search-eval %-8s n=%3d recall@10=%.4f floor=%.2f mrr@10=%.4f floor=%.2f "
				+ "zeroResultRate=%.4f").formatted(entry.getKey(), entry.getValue().size(), recall(entry.getValue()),
				RECALL_FLOORS.get(entry.getKey()), mrr(entry.getValue()), MRR_FLOORS.get(entry.getKey()),
				zeroResultRate(entry.getValue())))
			.collect(Collectors.joining("\n"));
		System.out.println(summary);
		assertThat(sut).containsOnlyKeys(RECALL_FLOORS.keySet());
		// 한 유형만 떨어져도 다른 유형과 함께 봐야 원인을 좁힐 수 있어 실패 메시지에 모든 유형을 싣는다
		assertSoftly(softly -> sut.forEach((type, results) -> {
			softly.assertThat(recall(results))
				.as("%s recall@10이 하한보다 낮다. 전체 결과:%n%s", type, summary)
				.isGreaterThanOrEqualTo(RECALL_FLOORS.get(type));
			softly.assertThat(mrr(results))
				.as("%s MRR@10이 하한보다 낮다. 전체 결과:%n%s", type, summary)
				.isGreaterThanOrEqualTo(MRR_FLOORS.get(type));
		}));
	}

	private QueryResult evaluate(EvalQuery query) {
		ProductSearchKeyword keyword = ProductSearchKeyword.from(query.query()).orElseThrow();
		List<Long> top = buyerProductQueryRepository.findVisibleProductIds(keyword, null, null, null,
			ProductSort.RELEVANCE, PRODUCTION_MIN_SCORE, PageRequest.of(0, TOP_K)).getContent();
		long hits = top.stream().filter(query.relevant()::contains).count();
		double recall = (double)hits / Math.min(TOP_K, query.relevant().size());
		double reciprocalRank = IntStream.range(0, top.size())
			.filter(i -> query.relevant().contains(top.get(i)))
			.mapToDouble(i -> 1.0 / (i + 1))
			.findFirst()
			.orElse(0);
		return new QueryResult(query.type(), recall, reciprocalRank, top.isEmpty());
	}

	private static double recall(List<QueryResult> results) {
		return results.stream().mapToDouble(QueryResult::recall).average().orElseThrow();
	}

	private static double mrr(List<QueryResult> results) {
		return results.stream().mapToDouble(QueryResult::reciprocalRank).average().orElseThrow();
	}

	private static double zeroResultRate(List<QueryResult> results) {
		return (double)results.stream().filter(QueryResult::zeroResult).count() / results.size();
	}

	private int count(String tableAndCondition) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + SCHEMA + "." + tableAndCondition, Integer.class);
	}

	private static List<String> readLines(String path) throws IOException {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
	}

	private void deleteAll() {
		// DELETE는 FULLTEXT 인덱스에 지운 문서를 남겨 점수(IDF)가 앞서 돈 테스트에 따라 달라진다. TRUNCATE는 인덱스를 새로 만든다
		jdbcTemplate.execute("TRUNCATE TABLE " + SCHEMA + ".product_search");
		// 태그와 태그 판정 행이 상품을 외래키로 가리키므로 먼저 지운다
		productTagRepository.deleteAllInBatch();
		productTagJudgmentRepository.deleteAllInBatch();
		productRepository.deleteAll();
	}

	private record EvalQuery(String id, String type, String query, Set<Long> relevant) {

		// 평가셋은 따옴표로 감싼 필드가 없어 쉼표로만 나눈다. 열: query_id,type,query,intent_id,source,relevant_ids
		static EvalQuery parse(String line) {
			String[] fields = line.split(",", -1);
			Set<Long> relevant = Arrays.stream(fields[5].split("\\|"))
				.map(Long::valueOf)
				.collect(Collectors.toSet());
			return new EvalQuery(fields[0], fields[1], fields[2], relevant);
		}
	}

	private record QueryResult(String type, double recall, double reciprocalRank, boolean zeroResult) {
	}
}
