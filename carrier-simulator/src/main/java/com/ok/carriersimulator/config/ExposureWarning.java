package com.ok.carriersimulator.config;

import java.net.InetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.stereotype.Component;

/**
 * 서버가 이 컴퓨터 밖에서도 접속되는 주소로 열렸으면 기동할 때 경고를 남긴다.
 * 이 서버에는 로그인이 없고, 알림 주소로 받은 곳에 서버가 직접 요청을 보내므로 밖으로 열면
 * 같은 네트워크의 누구나 송장을 지우거나 이 서버를 거쳐 내부 주소로 요청을 보낼 수 있다.
 */
@Slf4j
@Component
class ExposureWarning implements ApplicationRunner {

    private final ServerProperties serverProperties;

    ExposureWarning(ServerProperties serverProperties) {
        this.serverProperties = serverProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        InetAddress address = serverProperties.getAddress();
        if (isExposed(address)) {
            log.warn("로그인이 없는 시험용 서버가 {} 주소로 열려 있습니다. 같은 네트워크의 누구나 송장을 지우거나 이 서버를 거쳐 "
                    + "내부 주소로 요청을 보낼 수 있습니다. CARRIER_SIMULATOR_ADDRESS를 127.0.0.1로 되돌리세요.",
                    address == null ? "모든" : address.getHostAddress());
        }
    }

    /**
     * 주소를 정하지 않으면 서버는 모든 주소에서 받는다.
     */
    static boolean isExposed(InetAddress address) {
        return address == null || !address.isLoopbackAddress();
    }
}
