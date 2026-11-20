package com.ok.product.product.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ok.product.product.application.port.out.ProductTagRepository;
import com.ok.product.product.domain.ProductTag;

public interface ProductTagJpaRepository extends JpaRepository<ProductTag, Long>, ProductTagRepository {

	// JpaRepository와 포트가 같은 메서드를 다른 제네릭 형태로 물려줘 이 타입으로 부르면 모호한 호출이 된다.
	// 여기서 다시 선언해 하나로 합친다. 구현은 그대로 Spring Data 기본 구현이다
	@Override
	<S extends ProductTag> List<S> saveAll(Iterable<S> productTags);

	@Override
	void deleteAll(Iterable<? extends ProductTag> productTags);
}
