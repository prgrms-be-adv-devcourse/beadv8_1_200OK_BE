package com.ok.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.ok.common.exception.RestApiException;
import com.ok.product.product.domain.Product;
import com.ok.product.product.domain.ProductCategory;
import com.ok.product.product.domain.ProductOption;
import com.ok.product.product.domain.ProductOptionSpec;
import com.ok.product.product.domain.ProductRegion;
import com.ok.product.product.domain.ProductStatus;
import com.ok.product.product.domain.ProductVisibility;
import com.ok.product.product.exception.ProductErrorCode;
import com.ok.product.product.repository.ProductOptionRepository;
import com.ok.product.product.repository.ProductRepository;
import com.ok.product.product.repository.ProductStockAllocationRepository;
import com.ok.product.product.service.ProductStockService;
import com.ok.product.product.service.dto.StockChange;
import com.ok.testsupport.AbstractIntegrationTest;

@AutoConfigureMockMvc
class ProductInternalIntegrationTest extends AbstractIntegrationTest {

	private static final String BUCKET_URL = "https://test-mock-bucket.s3.ap-northeast-2.amazonaws.com/product/";
	private static final Long SELLER_ID = 7L;

	// 테스트끼리 배정 기록이 섞이지 않도록 주문 ID를 겹치지 않게 발급한다
	private static final AtomicLong ORDER_IDS = new AtomicLong(1_000_000);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ProductRepository productRepository;

	@Autowired
	ProductOptionRepository productOptionRepository;

	@Autowired
	ProductStockAllocationRepository allocationRepository;

	@Autowired
	ProductStockService productStockService;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Test
	void 옵션_정보를_모든_필드와_함께_조회한다() throws Exception {
		// given
		Product product = onSale(List.of(ProductOptionSpec.newOption("3kg 특품", 40_000L, 32_000L, 147)));
		productRepository.save(product);
		ProductOption option = product.getOptions().getFirst();

		// when & then
		mockMvc.perform(get("/internal/v1/products/options").param("optionIds", option.getId().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].optionId").value(option.getId()))
			.andExpect(jsonPath("$[0].productId").value(product.getId()))
			.andExpect(jsonPath("$[0].sellerId").value(SELLER_ID))
			.andExpect(jsonPath("$[0].productName").value("청송 꿀사과"))
			.andExpect(jsonPath("$[0].optionName").value("3kg 특품"))
			.andExpect(jsonPath("$[0].productThumbnailUrl").value(BUCKET_URL + "a.jpg"))
			.andExpect(jsonPath("$[0].listPrice").value(40_000))
			.andExpect(jsonPath("$[0].salePrice").value(32_000))
			.andExpect(jsonPath("$[0].status").value("ON_SALE"))
			.andExpect(jsonPath("$[0].visibility").value("VISIBLE"))
			.andExpect(jsonPath("$[0].productDeleted").value(false))
			.andExpect(jsonPath("$[0].optionDeleted").value(false))
			.andExpect(jsonPath("$[0].availableStock").value(147))
			.andExpect(jsonPath("$[0].purchasable").value(true));
	}

	@Test
	void 판매_조건이나_재고를_만족하지_않으면_구매할_수_없다() throws Exception {
		// given
		Long ready = firstOptionId(save(withStatus(ProductStatus.READY)));
		Long paused = firstOptionId(save(withStatus(ProductStatus.PAUSED)));
		Long ended = firstOptionId(save(withStatus(ProductStatus.ENDED)));
		Product hiddenProduct = onSale(option(10));
		hiddenProduct.changeVisibility(ProductVisibility.HIDDEN);
		Long hidden = firstOptionId(save(hiddenProduct));
		Long noStock = firstOptionId(save(onSale(option(0))));

		// when & then
		mockMvc.perform(get("/internal/v1/products/options")
				.param("optionIds", joinIds(ready, paused, ended, hidden, noStock)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].optionId")
				.value(contains(ready.intValue(), paused.intValue(), ended.intValue(), hidden.intValue(),
					noStock.intValue())))
			.andExpect(jsonPath("$[*].purchasable").value(contains(false, false, false, false, false)))
			.andExpect(jsonPath("$[4].availableStock").value(0));
	}

	@Test
	void 삭제된_상품과_옵션은_플래그와_함께_포함하고_없는_ID는_뺀다() throws Exception {
		// given
		Product deletedProduct = onSale(option(10));
		deletedProduct.delete(LocalDateTime.now());
		Long ofDeletedProduct = firstOptionId(save(deletedProduct));

		Product product = save(onSale(List.of(
			ProductOptionSpec.newOption("남길 옵션", null, 30_000L, 10),
			ProductOptionSpec.newOption("지울 옵션", null, 30_000L, 10))));
		Long kept = product.getOptions().get(0).getId();
		Long deletedOption = product.getOptions().get(1).getId();
		deleteOption(product.getId(), kept);

		// when & then
		mockMvc.perform(get("/internal/v1/products/options")
				.param("optionIds", joinIds(ofDeletedProduct, kept, deletedOption, Long.MAX_VALUE)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].optionId")
				.value(contains(ofDeletedProduct.intValue(), kept.intValue(), deletedOption.intValue())))
			.andExpect(jsonPath("$[*].productDeleted").value(contains(true, false, false)))
			.andExpect(jsonPath("$[*].optionDeleted").value(contains(true, false, true)))
			.andExpect(jsonPath("$[*].purchasable").value(contains(false, true, false)));
	}

	@ParameterizedTest
	@MethodSource("잘못된_옵션_ID_목록")
	void 옵션_ID가_없거나_100개를_넘으면_400(String optionIds) throws Exception {
		mockMvc.perform(get("/internal/v1/products/options").param("optionIds", optionIds))
			.andExpect(status().isBadRequest());
	}

	static Stream<String> 잘못된_옵션_ID_목록() {
		return Stream.of(
			"",
			LongStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(",")),
			"abc");
	}

	@Test
	void 옵션_ID_파라미터가_없으면_400() throws Exception {
		mockMvc.perform(get("/internal/v1/products/options"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 배정하면_판매_가능_수량이_요청_수량만큼_줄어든다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();

		// when
		allocate(orderId, item(optionId, 3))
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(7);
	}

	@Test
	void 같은_주문으로_다시_배정해도_한_번만_반영된다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();
		allocate(orderId, item(optionId, 3));

		// when
		allocate(orderId, item(optionId, 3))
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(7);
	}

	@Test
	void 같은_옵션이_여러_줄이면_합쳐서_배정한다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();

		// when
		allocate(orderId, item(optionId, 2), item(optionId, 3))
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(5);
		assertThat(allocationRepository.findAll())
			.filteredOn(allocation -> allocation.getOrderId().equals(orderId))
			.singleElement()
			.satisfies(allocation -> assertThat(allocation.getQuantity()).isEqualTo(5));
	}

	// 테스트 컨텍스트는 problem type이 about:blank라 응답으로는 409 종류를 구분할 수 없다.
	// HTTP로는 상태 코드를, 서비스 직접 호출로는 에러 코드를 확인한다
	@Test
	void 배정할_수_없으면_404나_409로_응답한다() throws Exception {
		// given
		Long paused = firstOptionId(save(withStatus(ProductStatus.PAUSED)));
		Long noStock = firstOptionId(save(onSale(option(0))));

		// when & then
		allocate(ORDER_IDS.incrementAndGet(), item(Long.MAX_VALUE, 1))
			.andExpect(status().isNotFound());
		allocate(ORDER_IDS.incrementAndGet(), item(paused, 1))
			.andExpect(status().isConflict());
		allocate(ORDER_IDS.incrementAndGet(), item(noStock, 1))
			.andExpect(status().isConflict());
	}

	@Test
	void 재고가_부족하면_먼저_배정한_옵션까지_되돌린다() {
		// given
		Product product = save(onSale(List.of(
			ProductOptionSpec.newOption("넉넉한 옵션", null, 30_000L, 10),
			ProductOptionSpec.newOption("모자란 옵션", null, 30_000L, 1))));
		Long enough = product.getOptions().get(0).getId();
		Long lacking = product.getOptions().get(1).getId();
		Long orderId = ORDER_IDS.incrementAndGet();

		// when & then
		assertThatThrownBy(() -> productStockService.allocate(orderId,
			List.of(new StockChange(enough, 2), new StockChange(lacking, 5))))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK));
		assertThat(availableStock(enough)).isEqualTo(10);
		assertThat(availableStock(lacking)).isEqualTo(1);
		assertThat(allocationRepository.existsByOrderId(orderId)).isFalse();
	}

	@Test
	void 재고가_0인_옵션도_재고_부족으로_판정한다() {
		// given
		Long optionId = firstOptionId(save(onSale(option(0))));

		// when & then
		assertThatThrownBy(() -> productStockService.allocate(ORDER_IDS.incrementAndGet(),
			List.of(new StockChange(optionId, 1))))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK));
	}

	@ParameterizedTest
	@MethodSource("판매_중이_아닌_상품")
	void 판매_중이_아닌_옵션은_배정하지_않는다(Product product) {
		// given
		Long optionId = firstOptionId(save(product));

		// when & then
		assertThatThrownBy(() -> productStockService.allocate(ORDER_IDS.incrementAndGet(),
			List.of(new StockChange(optionId, 1))))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.OPTION_NOT_PURCHASABLE));
		assertThat(availableStock(optionId)).isEqualTo(10);
	}

	Stream<Product> 판매_중이_아닌_상품() {
		Product hidden = onSale(option(10));
		hidden.changeVisibility(ProductVisibility.HIDDEN);
		Product deleted = onSale(option(10));
		deleted.delete(LocalDateTime.now());
		return Stream.of(withStatus(ProductStatus.READY), withStatus(ProductStatus.PAUSED),
			withStatus(ProductStatus.ENDED), hidden, deleted);
	}

	@Test
	void 없는_옵션은_배정하지_않는다() {
		assertThatThrownBy(() -> productStockService.allocate(ORDER_IDS.incrementAndGet(),
			List.of(new StockChange(Long.MAX_VALUE, 1))))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.OPTION_NOT_FOUND));
	}

	@ParameterizedTest
	@MethodSource("잘못된_배정_요청")
	void 배정_요청_형식이_잘못되면_422(String body) throws Exception {
		mockMvc.perform(post("/internal/v1/products/stock-allocations")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnprocessableContent());
	}

	static Stream<String> 잘못된_배정_요청() {
		return Stream.of(
			"""
				{"items": [{"optionId": 1, "quantity": 1}]}""",
			"""
				{"orderId": 1, "items": []}""",
			"""
				{"orderId": 1}""",
			"""
				{"orderId": 1, "items": [{"optionId": 1, "quantity": 0}]}""",
			"""
				{"orderId": 1, "items": [{"quantity": 1}]}""");
	}

	@Test
	void 해제하면_배정한_수량이_돌아온다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();
		allocate(orderId, item(optionId, 4));

		// when
		release(orderId)
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(10);
	}

	@Test
	void 두_번_해제해도_한_번만_돌아온다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long first = ORDER_IDS.incrementAndGet();
		Long second = ORDER_IDS.incrementAndGet();
		allocate(first, item(optionId, 4));
		allocate(second, item(optionId, 3));

		// when
		release(first);
		release(first)
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(7);
	}

	@Test
	void 같은_주문으로_동시에_배정하면_한_번만_반영되고_나머지는_204나_409다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();
		int requests = 5;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return allocate(orderId, item(optionId, 2)).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
		}

		// then
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsOnly(204, 409).contains(204);
		assertThat(availableStock(optionId)).isEqualTo(8);
	}

	@Test
	void 해제한_주문으로_다시_배정하면_거부하고_재고는_그대로다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();
		allocate(orderId, item(optionId, 4));
		release(orderId);

		// when & then
		assertThatThrownBy(() -> productStockService.allocate(orderId, List.of(new StockChange(optionId, 4))))
			.isInstanceOfSatisfying(RestApiException.class,
				e -> assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.ALLOCATION_ALREADY_RELEASED));
		allocate(orderId, item(optionId, 4))
			.andExpect(status().isConflict());
		assertThat(availableStock(optionId)).isEqualTo(10);
	}

	@Test
	void 배정_기록이_없는_주문을_해제해도_204() throws Exception {
		release(ORDER_IDS.incrementAndGet())
			.andExpect(status().isNoContent());
	}

	@Test
	void 배정_후_삭제된_옵션도_해제한다() throws Exception {
		// given
		Product product = save(onSale(option(10)));
		Long optionId = firstOptionId(product);
		Long orderId = ORDER_IDS.incrementAndGet();
		allocate(orderId, item(optionId, 4));
		transactionTemplate.executeWithoutResult(
			status -> productRepository.findById(product.getId()).orElseThrow().delete(LocalDateTime.now()));

		// when
		release(orderId)
			.andExpect(status().isNoContent());

		// then
		assertThat(availableStock(optionId)).isEqualTo(10);
	}

	@Test
	void 같은_주문을_동시에_해제해도_한_번만_돌아온다() throws Exception {
		// given
		Long optionId = firstOptionId(save(onSale(option(10))));
		Long orderId = ORDER_IDS.incrementAndGet();
		allocate(orderId, item(optionId, 4));
		allocate(ORDER_IDS.incrementAndGet(), item(optionId, 3));
		int requests = 5;
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();

		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
			for (int i = 0; i < requests; i++) {
				results.add(executor.submit(() -> {
					start.await();
					return release(orderId).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
		}

		// then
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		assertThat(statuses).containsOnly(204);
		assertThat(availableStock(optionId)).isEqualTo(7);
	}

	@Test
	void 남은_재고보다_많은_주문이_동시에_배정하면_남은_재고만큼만_성공한다() throws Exception {
		// given
		int stock = 3;
		int orders = 10;
		Long optionId = firstOptionId(save(onSale(option(stock))));
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Boolean>> results = new ArrayList<>();

		// when
		try (ExecutorService executor = Executors.newFixedThreadPool(orders)) {
			for (int i = 0; i < orders; i++) {
				Long orderId = ORDER_IDS.incrementAndGet();
				results.add(executor.submit(() -> {
					start.await();
					try {
						productStockService.allocate(orderId, List.of(new StockChange(optionId, 1)));
						return true;
					} catch (RestApiException e) {
						assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.INSUFFICIENT_STOCK);
						return false;
					}
				}));
			}
			start.countDown();
		}

		// then
		long succeeded = 0;
		for (Future<Boolean> result : results) {
			if (result.get()) {
				succeeded++;
			}
		}
		assertThat(succeeded).isEqualTo(stock);
		assertThat(availableStock(optionId)).isZero();
	}

	@Test
	void 옵션_순서가_다른_주문이_동시에_배정해도_데드락_없이_모두_배정된다() throws Exception {
		// given
		int orders = 100;
		Product product = save(onSale(List.of(
			ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, orders),
			ProductOptionSpec.newOption("5kg", 60_000L, 52_000L, orders))));
		Long optionA = product.getOptions().get(0).getId();
		Long optionB = product.getOptions().get(1).getId();
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();

		// when: 절반은 [A,B], 절반은 [B,A] 순서로 요청한다
		try (ExecutorService executor = Executors.newFixedThreadPool(orders)) {
			for (int i = 0; i < orders; i++) {
				Long orderId = ORDER_IDS.incrementAndGet();
				List<StockChange> changes = i % 2 == 0
					? List.of(new StockChange(optionA, 1), new StockChange(optionB, 1))
					: List.of(new StockChange(optionB, 1), new StockChange(optionA, 1));
				results.add(executor.submit(() -> {
					start.await();
					productStockService.allocate(orderId, changes);
					return null;
				}));
			}
			start.countDown();
		}

		// then: 데드락으로 롤백된 주문이 있으면 get()이 예외를 던진다
		for (Future<?> result : results) {
			result.get();
		}
		assertThat(availableStock(optionA)).isZero();
		assertThat(availableStock(optionB)).isZero();
	}

	private ResultActions allocate(Long orderId, String... items) throws Exception {
		return mockMvc.perform(post("/internal/v1/products/stock-allocations")
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"orderId": %d, "items": [%s]}""".formatted(orderId, String.join(",", items))));
	}

	private ResultActions release(Long orderId) throws Exception {
		return mockMvc.perform(post("/internal/v1/products/stock-allocations/{orderId}/release", orderId));
	}

	private String item(Long optionId, int quantity) {
		return """
			{"optionId": %d, "quantity": %d}""".formatted(optionId, quantity);
	}

	private int availableStock(Long optionId) {
		return productOptionRepository.findById(optionId).orElseThrow().availableStock();
	}

	private Product save(Product product) {
		return productRepository.save(product);
	}

	private Long firstOptionId(Product product) {
		return product.getOptions().getFirst().getId();
	}

	// 옵션 삭제는 상품 수정(옵션 병합)으로만 일어나므로, 남길 옵션만 보내 나머지를 삭제한다
	private void deleteOption(Long productId, Long keptOptionId) {
		transactionTemplate.executeWithoutResult(status -> {
			Product product = productRepository.findById(productId).orElseThrow();
			product.mergeOptions(
				List.of(new ProductOptionSpec(keptOptionId, "남길 옵션", null, 30_000L, 10)), LocalDateTime.now());
		});
	}

	private String joinIds(Long... ids) {
		return Stream.of(ids).map(String::valueOf).collect(Collectors.joining(","));
	}

	private Product withStatus(ProductStatus status) {
		Product product = onSale(option(10));
		product.changeStatus(status);
		return product;
	}

	private Product onSale(List<ProductOptionSpec> options) {
		Product product = Product.create(SELLER_ID, "청송 꿀사과", "<p>설명</p>", ProductCategory.FRUIT,
			ProductRegion.GYEONGBUK, options, List.of(BUCKET_URL + "a.jpg"));
		product.changeStatus(ProductStatus.ON_SALE);
		product.changeVisibility(ProductVisibility.VISIBLE);
		return product;
	}

	private List<ProductOptionSpec> option(int totalStock) {
		return List.of(ProductOptionSpec.newOption("3kg", 40_000L, 32_000L, totalStock));
	}
}
