package com.ok;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 지갑 모듈을 단독으로 띄우는 앱 클래스.
 *
 * <p>Spring Modulith가 앱 클래스의 패키지를 루트로 보고 그 바로 아래 패키지를 모듈로 인식하므로 {@code com.ok}에 둔다.
 * 모듈 앱 클래스는 모듈마다 이름이 다르다. monolith에서는 다른 앱 클래스가 클래스패스에 함께 보이기 때문이다.
 *
 * <p>모든 모듈을 분리하기 전에는 monolith의 테스트가 설정 클래스를 자동 탐색할 때 이 클래스와 충돌하지 않도록
 * 테스트 소스에 둔다(테스트 소스는 monolith 클래스패스에 들어가지 않는다).
 */
@SpringBootApplication
public class WalletApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletApplication.class, args);
    }
}
