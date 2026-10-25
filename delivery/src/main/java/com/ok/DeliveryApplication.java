package com.ok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 배송 모듈을 단독으로 띄우는 앱 클래스.
 *
 * <p>Spring Modulith가 앱 클래스의 패키지를 루트로 보고 그 바로 아래 패키지를 모듈로 인식하므로 {@code com.ok}에 둔다.
 * 모듈 앱 클래스는 모듈마다 이름이 다르다. monolith에서는 다른 앱 클래스가 클래스패스에 함께 보이기 때문이다.
 * monolith는 이 클래스를 설정 클래스로 함께 스캔하므로 {@code main}과 {@code @SpringBootApplication} 외에는 얹지 않는다.
 */
@SpringBootApplication
public class DeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryApplication.class, args);
    }
}
