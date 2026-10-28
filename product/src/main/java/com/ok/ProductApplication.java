package com.ok;

import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.ok.common.StandaloneApplication;

/**
 * 상품 모듈을 단독으로 띄우는 앱 클래스.
 *
 * <p>Spring Modulith가 앱 클래스의 패키지를 모듈 루트로 보므로 {@code com.ok}에 둔다.
 * monolith에서 모든 모듈의 앱 클래스가 함께 보이므로 이름을 모듈마다 달리하고, 함께 스캔되므로 {@code main}과 {@code @SpringBootApplication} 외에는 얹지 않는다.
 */
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        StandaloneApplication.run(ProductApplication.class, "product", args);
    }
}
